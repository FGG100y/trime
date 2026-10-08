/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import kotlinx.serialization.Serializable

/** The kind of sherpa-onnx offline model a [VoiceModel] directory contains. */
@Serializable
enum class VoiceModelType {
    SENSE_VOICE,
    PARAFORMER,
    WHISPER,
    TRANSDUCER,
    ZIPFORMER_CTC,
}

/**
 * Metadata of an imported, on-device speech recognition model.
 *
 * All file fields are names relative to the model's own directory (see
 * [VoiceModelManager.modelDir]); empty when the model type does not need them.
 */
@Serializable
data class VoiceModel(
    val id: String,
    val name: String,
    val type: VoiceModelType,
    val model: String = "",
    val tokens: String = "",
    val encoder: String = "",
    val decoder: String = "",
    val joiner: String = "",
)
