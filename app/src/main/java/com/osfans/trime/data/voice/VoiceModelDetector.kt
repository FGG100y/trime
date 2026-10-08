/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

/**
 * Whether [dirName] names one of sherpa-onnx's *streaming* model families,
 * such as `sherpa-onnx-streaming-zipformer-...` or
 * `sherpa-onnx-x-asr-480ms-streaming-zipformer-...`.
 *
 * The `non-streaming` NeMo releases must not be swept up by the match.
 */
private fun looksStreaming(dirName: String): Boolean = dirName.contains("streaming", ignoreCase = true) &&
    !dirName.contains("non-streaming", ignoreCase = true)

/**
 * Best-effort detection of the model type from the folder name and the files
 * inside it.
 *
 * Trime only drives sherpa-onnx *offline* recognizers, so streaming model
 * folders are rejected up front: their files look like an offline transducer
 * or CTC model, but loading them fails deep inside the native library with an
 * unhelpful message. This is why the offline and streaming `x-asr` variants,
 * which share the same `encoder/decoder/joiner` file names, are told apart by
 * the folder name.
 *
 * A wrong guess otherwise only affects which files are wired into the
 * sherpa-onnx config; the user can delete and re-import.
 *
 * @throws IllegalArgumentException when the folder does not look like a
 *   supported offline model.
 */
internal fun detectVoiceModel(dirName: String, files: List<String>): VoiceModel {
    val onnx = files.filter { it.endsWith(".onnx", ignoreCase = true) }
    if (onnx.isEmpty()) {
        throw IllegalArgumentException("no .onnx model found")
    }
    if (looksStreaming(dirName)) {
        throw IllegalArgumentException(
            "streaming models are not supported, import an offline model instead " +
                "(the folder name contains \"streaming\")",
        )
    }
    val tokens = files.firstOrNull { it.contains("tokens", ignoreCase = true) }

    val encoder = choose(onnx, "encoder")
    val decoder = choose(onnx, "decoder")
    val joiner = choose(onnx, "joiner")

    val lowerName = dirName.lowercase()
    return when {
        joiner != null -> VoiceModel(
            id = "",
            name = dirName,
            type = VoiceModelType.TRANSDUCER,
            tokens = tokens.orEmpty(),
            encoder = encoder.orEmpty(),
            decoder = decoder ?: "",
            joiner = joiner,
        )

        encoder != null && decoder != null && lowerName.contains("whisper") ->
            VoiceModel(
                id = "",
                name = dirName,
                type = VoiceModelType.WHISPER,
                tokens = tokens.orEmpty(),
                encoder = encoder,
                decoder = decoder,
            )

        lowerName.contains("paraformer") -> VoiceModel(
            id = "",
            name = dirName,
            type = VoiceModelType.PARAFORMER,
            model = choose(onnx, "model") ?: onnx.first(),
            tokens = requireTokens(tokens),
        )

        lowerName.contains("zipformer") && lowerName.contains("ctc") -> VoiceModel(
            id = "",
            name = dirName,
            type = VoiceModelType.ZIPFORMER_CTC,
            model = choose(onnx, "model") ?: onnx.first(),
            tokens = requireTokens(tokens),
        )

        else -> VoiceModel(
            id = "",
            name = dirName,
            type = VoiceModelType.SENSE_VOICE,
            model = choose(onnx, "model") ?: onnx.first(),
            tokens = requireTokens(tokens),
        )
    }
}

private fun requireTokens(tokens: String?): String = tokens ?: throw IllegalArgumentException("tokens.txt not found")

/** Picks the best matching onnx file, preferring int8/fp16 quantizations. */
private fun choose(onnx: List<String>, keyword: String): String? = onnx
    .filter { it.contains(keyword, ignoreCase = true) }
    .minByOrNull { name ->
        when {
            name.contains("int8", ignoreCase = true) -> 0
            name.contains("fp16", ignoreCase = true) -> 1
            else -> 2
        }
    }
