/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import timber.log.Timber
import java.io.File

/**
 * [AsrEngine] backed by sherpa-onnx offline (non streaming) recognizers.
 *
 * The native library is shipped through `app/libs/sherpa-onnx.jar` and the
 * `jniLibs` `.so` files, see `script/fetch-sherpa-onnx.sh`.
 */
class SherpaOnnxEngine : AsrEngine {
    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null

    override fun load(model: VoiceModel, language: String) {
        val key = "${model.id}:$language"
        if (recognizer != null && loadedKey == key) return
        release()

        val dir = VoiceModelManager.modelDir(model.id)
        require(dir.isDirectory) { "model directory does not exist: ${model.id}" }

        val config = buildConfig(model, dir, language)
        Timber.i("Loading voice model %s (%s)", model.name, model.type)
        recognizer = OfflineRecognizer(config = config)
        loadedKey = key
    }

    override fun recognize(samples: FloatArray): String {
        val current = recognizer ?: error("recognizer is not loaded")
        val stream = current.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            current.decode(stream)
            current.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
        loadedKey = null
    }

    private fun buildConfig(
        model: VoiceModel,
        dir: File,
        language: String,
    ): OfflineRecognizerConfig {
        fun path(name: String) = File(dir, name).absolutePath

        val modelConfig = OfflineModelConfig(
            tokens = path(model.tokens),
            numThreads = 2,
            provider = "cpu",
        )

        when (model.type) {
            VoiceModelType.SENSE_VOICE -> {
                modelConfig.modelType = "sense_voice"
                modelConfig.senseVoice = OfflineSenseVoiceModelConfig(
                    model = path(model.model),
                    language = language,
                    useInverseTextNormalization = true,
                )
            }

            VoiceModelType.PARAFORMER -> {
                modelConfig.modelType = "paraformer"
                modelConfig.paraformer = OfflineParaformerModelConfig(
                    model = path(model.model),
                )
            }

            VoiceModelType.WHISPER -> {
                modelConfig.modelType = "whisper"
                modelConfig.whisper = OfflineWhisperModelConfig(
                    encoder = path(model.encoder),
                    decoder = path(model.decoder),
                    language = if (language == "auto") "en" else language,
                    task = "transcribe",
                )
            }

            VoiceModelType.TRANSDUCER -> {
                modelConfig.modelType = "transducer"
                modelConfig.transducer = OfflineTransducerModelConfig(
                    encoder = path(model.encoder),
                    decoder = path(model.decoder),
                    joiner = path(model.joiner),
                )
            }

            VoiceModelType.ZIPFORMER_CTC -> {
                modelConfig.modelType = "zipformer2_ctc"
                modelConfig.zipformerCtc = OfflineZipformerCtcModelConfig(
                    model = path(model.model),
                )
            }
        }

        return OfflineRecognizerConfig(modelConfig = modelConfig)
    }
}
