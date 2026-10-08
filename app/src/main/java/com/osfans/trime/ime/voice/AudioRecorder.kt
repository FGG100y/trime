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
    private val samples = ArrayList<Float>(sampleRate * 16)
    private val lock = Any()

    @Volatile
    private var recording = false

    val isRecording: Boolean get() = recording

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
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

        synchronized(lock) { samples.clear() }
        audioRecord = record
        recording = true
        record.startRecording()
        worker = Thread({ readLoop(record) }, "trime-voice-recorder").apply { start() }
        return true
    }

    private fun readLoop(record: AudioRecord) {
        val buffer = ShortArray(1024)
        while (recording) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            var sum = 0.0
            synchronized(lock) {
                for (i in 0 until read) {
                    val value = buffer[i] / 32768f
                    samples.add(value)
                    sum += value.toDouble() * value.toDouble()
                }
            }
            _amplitude.value = sqrt(sum / read).toFloat()
        }
    }

    /** Stops the capture and returns everything recorded so far. */
    fun stop(): FloatArray {
        if (!recording) return FloatArray(0)
        recording = false
        worker?.join(500)
        worker = null
        audioRecord?.let { record ->
            runCatching { record.stop() }
            record.release()
        }
        audioRecord = null
        _amplitude.value = 0f
        return synchronized(lock) { samples.toFloatArray() }
    }

    /** Stops the capture and discards the buffered audio. */
    fun cancel() {
        stop()
        synchronized(lock) { samples.clear() }
    }
}
