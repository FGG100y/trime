/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono PCM from the microphone and converts it to a float
 * array in the range `[-1, 1]`, which is what sherpa-onnx expects.
 *
 * Audio is handed to [start]'s callback block by block and is **not** retained:
 * segmentation and recognition happen as the user speaks, so a session can run
 * for as long as the user wants without memory growing with it.
 *
 * Callers must hold the `RECORD_AUDIO` permission.
 */
class AudioRecorder(
    private val sampleRate: Int = 16_000,
) {
    private val _amplitude = MutableStateFlow(0f)

    /** Normalised RMS of the last read block, for a simple level meter. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var recording = false

    // Called from the capture thread for every block, so a session can feed a
    // live segmenter instead of waiting for stop().
    @Volatile
    private var onBlock: ((FloatArray) -> Unit)? = null

    val isRecording: Boolean get() = recording

    /**
     * Starts capturing.
     *
     * @param onBlock invoked on the capture thread with each block of samples.
     *   Blocks are only ever handed out while capturing, and never after [stop]
     *   returns.
     */
    @SuppressLint("MissingPermission")
    fun start(onBlock: ((FloatArray) -> Unit)? = null): Boolean {
        if (recording) return true

        val minBuffer =
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            Timber.e("Invalid min buffer size for AudioRecord: %d", minBuffer)
            return false
        }

        val record =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2,
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to create AudioRecord")
                return false
            }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        audioRecord = record
        try {
            record.startRecording()
        } catch (e: Exception) {
            Timber.e(e, "Failed to start AudioRecord")
            record.release()
            audioRecord = null
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Timber.e("AudioRecord did not enter the recording state")
            record.release()
            audioRecord = null
            return false
        }
        recording = true
        this.onBlock = onBlock
        worker = Thread({ readLoop(record) }, "trime-voice-recorder").apply { start() }
        return true
    }

    private fun readLoop(record: AudioRecord) {
        val buffer = ShortArray(1024)
        while (recording) {
            val read = record.read(buffer, 0, buffer.size)
            if (read < 0) {
                // A negative result is returned immediately (dead object,
                // invalid state, ...); continuing would spin the CPU. Stop the
                // loop and let stop() release the AudioRecord.
                Timber.e("AudioRecord read failed with error %d", read)
                break
            }
            if (read == 0) continue
            val block = FloatArray(read)
            var sum = 0.0
            for (i in 0 until read) {
                val value = buffer[i] / 32768f
                block[i] = value
                sum += value.toDouble() * value.toDouble()
            }
            _amplitude.value = sqrt(sum / read).toFloat()
            runCatching { onBlock?.invoke(block) }
                .onFailure { Timber.e(it, "Voice block callback failed") }
        }
    }

    /** Stops the capture and releases the microphone. */
    fun stop() {
        if (!recording) return
        recording = false
        worker?.join(500)
        worker = null
        onBlock = null
        audioRecord?.let { record ->
            runCatching { record.stop() }
            record.release()
        }
        audioRecord = null
        _amplitude.value = 0f
    }

    /** Stops the capture. */
    fun cancel() {
        stop()
    }
}
