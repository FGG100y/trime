/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pins [detectVoiceModel] against the file layouts of the models the docs
 * recommend, in particular the offline `x-asr` transducer release whose
 * `encoder`/`decoder`/`joiner` names are shared with its streaming sibling.
 */
class VoiceModelDetectorTest :
    StringSpec({

        "detects the offline x-asr transducer release (int8)" {
            val model =
                detectVoiceModel(
                    "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03",
                    listOf(
                        "bpe.model",
                        "decoder-epoch-99-avg-1.onnx",
                        "encoder-epoch-99-avg-1.int8.onnx",
                        "joiner-epoch-99-avg-1.int8.onnx",
                        "tokens.txt",
                        "test_onnx.py",
                        "README.md",
                    ),
                )
            model.type shouldBe VoiceModelType.TRANSDUCER
            model.encoder shouldBe "encoder-epoch-99-avg-1.int8.onnx"
            model.decoder shouldBe "decoder-epoch-99-avg-1.onnx"
            model.joiner shouldBe "joiner-epoch-99-avg-1.int8.onnx"
            model.tokens shouldBe "tokens.txt"
        }

        "detects the offline x-asr transducer release (fp32)" {
            val model =
                detectVoiceModel(
                    "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-2026-06-03",
                    listOf(
                        "encoder-epoch-99-avg-1.onnx",
                        "decoder-epoch-99-avg-1.onnx",
                        "joiner-epoch-99-avg-1.onnx",
                        "tokens.txt",
                    ),
                )
            model.type shouldBe VoiceModelType.TRANSDUCER
            model.encoder shouldBe "encoder-epoch-99-avg-1.onnx"
            model.joiner shouldBe "joiner-epoch-99-avg-1.onnx"
        }

        "prefers the int8 encoder when a release ships both" {
            val model =
                detectVoiceModel(
                    "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-2026-06-03",
                    listOf(
                        "encoder-epoch-99-avg-1.onnx",
                        "encoder-epoch-99-avg-1.int8.onnx",
                        "decoder-epoch-99-avg-1.onnx",
                        "joiner-epoch-99-avg-1.onnx",
                        "joiner-epoch-99-avg-1.int8.onnx",
                        "tokens.txt",
                    ),
                )
            model.encoder shouldBe "encoder-epoch-99-avg-1.int8.onnx"
            model.joiner shouldBe "joiner-epoch-99-avg-1.int8.onnx"
            model.decoder shouldBe "decoder-epoch-99-avg-1.onnx"
        }

        "rejects the streaming x-asr release" {
            shouldThrow<IllegalArgumentException> {
                detectVoiceModel(
                    "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05",
                    listOf(
                        "decoder-epoch-99-avg-1.onnx",
                        "encoder-epoch-99-avg-1.int8.onnx",
                        "joiner-epoch-99-avg-1.int8.onnx",
                        "tokens.txt",
                    ),
                )
            }
        }

        "rejects the streaming zipformer release" {
            shouldThrow<IllegalArgumentException> {
                detectVoiceModel(
                    "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
                    listOf(
                        "encoder-epoch-99-avg-1.onnx",
                        "decoder-epoch-99-avg-1.onnx",
                        "joiner-epoch-99-avg-1.onnx",
                        "tokens.txt",
                    ),
                )
            }
        }

        "does not mistake a non-streaming release for a streaming one" {
            val model =
                detectVoiceModel(
                    "sherpa-onnx-nemo-ctc-fa-shenava-koochik-v1.0-non-streaming-2026-06-26",
                    listOf("model.onnx", "tokens.txt"),
                )
            model.tokens shouldBe "tokens.txt"
        }

        "detects the official SenseVoiceSmall release" {
            val model =
                detectVoiceModel(
                    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
                    listOf("model.int8.onnx", "tokens.txt", "README.md"),
                )
            model.type shouldBe VoiceModelType.SENSE_VOICE
            model.model shouldBe "model.int8.onnx"
        }

        "detects the Cantonese SenseVoice finetune" {
            val model =
                detectVoiceModel(
                    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
                    listOf("model.int8.onnx", "tokens.txt"),
                )
            model.type shouldBe VoiceModelType.SENSE_VOICE
            model.model shouldBe "model.int8.onnx"
        }

        "detects a Whisper release" {
            val model =
                detectVoiceModel(
                    "sherpa-onnx-whisper-turbo",
                    listOf("turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt"),
                )
            model.type shouldBe VoiceModelType.WHISPER
            model.encoder shouldBe "turbo-encoder.int8.onnx"
            model.decoder shouldBe "turbo-decoder.int8.onnx"
            model.tokens shouldBe "turbo-tokens.txt"
        }

        "detects a Paraformer release" {
            val model =
                detectVoiceModel(
                    "sherpa-onnx-paraformer-zh-int8-2025-10-07",
                    listOf("model.int8.onnx", "tokens.txt"),
                )
            model.type shouldBe VoiceModelType.PARAFORMER
            model.model shouldBe "model.int8.onnx"
        }

        "detects a Zipformer CTC release" {
            val model =
                detectVoiceModel(
                    "sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03",
                    listOf("model.int8.onnx", "tokens.txt"),
                )
            model.type shouldBe VoiceModelType.ZIPFORMER_CTC
            model.model shouldBe "model.int8.onnx"
        }

        "falls back to SenseVoice when the folder name is unknown" {
            val model = detectVoiceModel("my-model", listOf("model.int8.onnx", "tokens.txt"))
            model.type shouldBe VoiceModelType.SENSE_VOICE
        }

        "rejects a folder without an onnx model" {
            shouldThrow<IllegalArgumentException> {
                detectVoiceModel("sherpa-onnx-whatever", listOf("README.md", "tokens.txt"))
            }
        }

        "rejects a folder without tokens" {
            shouldThrow<IllegalArgumentException> {
                detectVoiceModel("sherpa-onnx-whatever", listOf("model.int8.onnx"))
            }
        }
    })
