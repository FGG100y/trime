/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

/**
 * A fully on-device speech recognition engine.
 *
 * Implementations are **not** thread safe. [load] and [recognize] are blocking
 * and must be called from a background thread. Audio is always 16 kHz mono
 * float PCM in the range [-1, 1].
 */
interface AsrEngine {
    /** Sample rate recognised by every [AsrEngine] implementation. */
    val sampleRate: Int get() = 16_000

    /**
     * Loads (or reuses) the recognizer for [model]. Cheap to call repeatedly
     * with the same model: implementations should cache the loaded model.
     *
     * @param language the model language code, e.g. `auto`, `zh`, `en`.
     */
    fun load(model: VoiceModel, language: String)

    /** Transcribes one utterance. [load] must have been called first. */
    fun recognize(samples: FloatArray): String

    /** Frees the native resources. Safe to call multiple times. */
    fun release()
}
