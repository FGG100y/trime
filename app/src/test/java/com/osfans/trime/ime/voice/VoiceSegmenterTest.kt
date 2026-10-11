/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the pause segmentation of the model-free fallback [EnergySegmenter].
 * The VAD used in production is [SileroVadSegmenter]; this covers the path taken
 * when its model cannot be loaded.
 */
class VoiceSegmenterTest :
    StringSpec({

        val blockMs = 20
        val blockSize = 16_000 * blockMs / 1000

        fun speech(blocks: Int = 1): List<FloatArray> = List(blocks) { FloatArray(blockSize) { 0.1f } }

        fun silence(blocks: Int = 1): List<FloatArray> = List(blocks) { FloatArray(blockSize) }

        fun feed(
            segmenter: EnergySegmenter,
            blocks: List<FloatArray>,
        ): List<FloatArray> = blocks.flatMap { segmenter.accept(it) }

        "emits one segment after the pause, ignoring leading silence" {
            val segmenter = EnergySegmenter()
            val emitted = feed(segmenter, silence(10) + speech(15) + silence(40))

            emitted.size shouldBe 1
            // ~300 ms of speech plus the trailing pause that closed it.
            emitted.single().size shouldBe (15 + 30) * blockSize
        }

        "keeps producing utterances for as long as the session runs" {
            val segmenter = EnergySegmenter()
            val emitted = feed(segmenter, speech(15) + silence(40) + speech(15) + silence(40))

            emitted.size shouldBe 2
        }

        "emits nothing while the user stays quiet after an utterance" {
            val segmenter = EnergySegmenter()
            val emitted = feed(segmenter, speech(15) + silence(400))

            emitted.size shouldBe 1
        }

        "discards a blip that never becomes real speech" {
            val segmenter = EnergySegmenter()
            val emitted = feed(segmenter, speech(2) + silence(60))

            emitted shouldBe emptyList()
        }

        "flush returns speech that was cut off mid-utterance" {
            val segmenter = EnergySegmenter()
            feed(segmenter, speech(20))

            segmenter.flush().size shouldBe 1
            segmenter.flush() shouldBe emptyList()
        }

        "flush drops a trailing blip" {
            val segmenter = EnergySegmenter()
            feed(segmenter, speech(2))

            segmenter.flush() shouldBe emptyList()
        }
    })
