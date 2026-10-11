/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.sqrt

/**
 * Splits a continuous capture into utterances.
 *
 * The offline recognizer cannot produce partial results, so the only way to let
 * the user see text while still speaking is to cut the stream at natural pauses
 * and transcribe each finished utterance immediately: the first sentence lands
 * in the editor long before the user stops talking. See `doc/voice-input.md`.
 *
 * Implementations are fed fixed-size sample blocks and are **not** thread
 * safe: a session must feed them from a single thread.
 */
interface VoiceSegmenter {
    /**
     * Feeds one block of 16 kHz mono float PCM and returns the utterances that
     * ended in this block, in order (usually zero or one).
     */
    fun accept(block: FloatArray): List<FloatArray>

    /** Ends the feed, returning whatever speech is still buffered. */
    fun flush(): List<FloatArray>

    /** Discards all state, so the next session starts clean. */
    fun reset()

    /**
     * Whether the block most recently fed to [accept] contained speech. Used to
     * measure how long the user has been silent.
     */
    val isSpeechDetected: Boolean

    /** Frees native resources, if any. Safe to call more than once. */
    fun release() = Unit
}

/**
 * [VoiceSegmenter] backed by the Silero VAD model shipped in `assets/`.
 *
 * A model-based VAD is used instead of an energy threshold on purpose: a
 * fixed threshold cannot follow a single sentence whose level drops several
 * times as the speaker trails off (a real capture measured 0.03 -> 0.004
 * within one utterance), so it both cut the sentence early and then ignored
 * the rest as silence. Silero decides speech/non-speech from the waveform, so
 * it does not depend on the device's absolute microphone level.
 *
 * @param assetManager used to read `silero_vad.onnx` out of the APK assets.
 */
class SileroVadSegmenter(
    assetManager: AssetManager,
    sampleRate: Int = 16_000,
) : VoiceSegmenter {
    private val vad =
        Vad(
            assetManager,
            VadModelConfig(
                sileroVadModelConfig =
                SileroVadModelConfig(
                    model = MODEL_ASSET,
                    threshold = 0.5f,
                    // A pause this long closes the utterance and commits it.
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    // Force a commit during a long monologue.
                    maxSpeechDuration = 15f,
                ),
                sampleRate = sampleRate,
                numThreads = 1,
                provider = "cpu",
            ),
        )

    override fun accept(block: FloatArray): List<FloatArray> {
        vad.acceptWaveform(block)
        return drain()
    }

    override fun flush(): List<FloatArray> {
        vad.flush()
        return drain()
    }

    override fun reset() {
        vad.reset()
        vad.clear()
    }

    override fun release() {
        vad.release()
    }

    override val isSpeechDetected: Boolean
        get() = vad.isSpeechDetected()

    private fun drain(): List<FloatArray> {
        val segments = mutableListOf<FloatArray>()
        while (!vad.empty()) {
            segments += vad.front().samples
            vad.pop()
        }
        return segments
    }

    private companion object {
        /** Bundled Silero VAD model, ~630 KB, MIT licensed. */
        const val MODEL_ASSET = "silero_vad.onnx"
    }
}

/**
 * A fallback [VoiceSegmenter] that needs no model at all: a block counts as
 * speech when its RMS is above a fixed threshold.
 *
 * It exists only for the case where the bundled VAD model cannot be loaded.
 * It is level dependent, so it is noticeably worse in practice; prefer
 * [SileroVadSegmenter].
 */
class EnergySegmenter(
    private val sampleRate: Int = 16_000,
    private val silenceThreshold: Float = DEFAULT_SILENCE_THRESHOLD,
    private val minSpeechMs: Int = 200,
    private val minSilenceMs: Int = 600,
    private val maxSegmentMs: Int = 15_000,
) : VoiceSegmenter {
    private val buffer = mutableListOf<FloatArray>()
    private var bufferedSamples = 0
    private var bufferedMs = 0
    private var speechMs = 0
    private var trailingSilenceMs = 0
    private var buffering = false
    private var lastIsSpeech = false

    override fun accept(block: FloatArray): List<FloatArray> {
        if (block.isEmpty()) return emptyList()
        val isSpeech = rms(block) >= silenceThreshold
        lastIsSpeech = isSpeech
        val blockMs = block.size * 1000 / sampleRate

        if (!buffering) {
            if (!isSpeech) return emptyList()
            buffering = true
            speechMs = 0
            trailingSilenceMs = 0
        }

        buffer += block
        bufferedSamples += block.size
        bufferedMs += blockMs
        if (isSpeech) {
            speechMs += blockMs
            trailingSilenceMs = 0
        } else {
            trailingSilenceMs += blockMs
        }

        val reachedMax = bufferedMs >= maxSegmentMs
        val reachedPause = speechMs >= minSpeechMs && trailingSilenceMs >= minSilenceMs
        val discardShortBlip = speechMs < minSpeechMs && trailingSilenceMs >= minSilenceMs
        if (reachedPause || reachedMax) return listOf(takeBuffer())
        if (discardShortBlip) clearBuffer()
        return emptyList()
    }

    override fun flush(): List<FloatArray> {
        if (!buffering) return emptyList()
        val segment = takeBuffer()
        if (segment.size < sampleRate * minSpeechMs / 1000) return emptyList()
        return listOf(segment)
    }

    override fun reset() {
        clearBuffer()
    }

    override val isSpeechDetected: Boolean
        get() = lastIsSpeech

    private fun clearBuffer() {
        buffer.clear()
        bufferedSamples = 0
        bufferedMs = 0
        speechMs = 0
        trailingSilenceMs = 0
        buffering = false
    }

    /** Joins and clears the buffered blocks. */
    private fun takeBuffer(): FloatArray {
        val result = FloatArray(bufferedSamples)
        var offset = 0
        buffer.forEach { block ->
            block.copyInto(result, offset)
            offset += block.size
        }
        clearBuffer()
        return result
    }

    private fun rms(block: FloatArray): Float {
        var sum = 0.0
        for (value in block) sum += value.toDouble() * value.toDouble()
        return sqrt(sum / block.size).toFloat()
    }

    private companion object {
        const val DEFAULT_SILENCE_THRESHOLD = 0.008f
    }
}
