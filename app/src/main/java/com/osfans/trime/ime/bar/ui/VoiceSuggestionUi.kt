/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ProgressBar
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.osfans.trime.R
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.ime.voice.VOICE_RECORDING_COLOR
import com.osfans.trime.util.rippleDrawable
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalMargin
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

/** Alpha of the level meter's track, out of 255. */
private const val LEVEL_TRACK_ALPHA = 60

/**
 * The voice input chip in the always-bar: the recording level, the recognising
 * status and the held transcript, in place of the toolbar while a session is
 * active.
 *
 * The microphone is the start/stop toggle. The trailing button is one control
 * that closes while the transcript is pending and undoes once it has been
 * inserted, so the same spot ends up as "undo (once), then close (again)".
 * All the callbacks are wired by `InputBarDelegate`.
 */
class VoiceSuggestionUi(
    override val ctx: Context,
    private val scope: ThemeScope,
) : Ui {
    private val colors get() = scope.colors

    var onStop: (() -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var onInsert: (() -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private var micHighlighted = false
    private var showingUndo = false

    private val icon =
        imageView {
            imageDrawable =
                drawable(R.drawable.ic_baseline_mic_24)!!.apply {
                    setTint(colors.candidateTextColor)
                }
        }

    private val text =
        textView {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(colors.candidateTextColor)
        }

    private val level =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(colors.hilitedCandidateTextColor)
            progressBackgroundTintList = ColorStateList.valueOf(levelTrackColor())
        }

    private val trailing =
        imageView {
            isFocusable = false
        }

    /** Holds the status text and the level meter in the same slot. */
    private val middle =
        frameLayout {
            add(text, lParams(matchParent, wrapContent) { gravity = Gravity.CENTER })
            add(level, lParams(matchParent, ctx.dp(6)) { gravity = Gravity.CENTER_VERTICAL })
        }

    private val layout =
        // The microphone and the trailing button are pinned to the two ends,
        // and the middle slot (text or level) fills whatever is left, so
        // switching between them never moves the ends.
        constraintLayout {
            val spacing = ctx.dp(6)
            add(
                icon,
                lParams(ctx.dp(20), ctx.dp(20)) {
                    startOfParent(ctx.dp(10))
                    centerVertically()
                },
            )
            add(
                middle,
                lParams(matchConstraints, wrapContent) {
                    after(icon, spacing)
                    before(trailing)
                    centerVertically()
                },
            )
            add(
                trailing,
                lParams(ctx.dp(20), ctx.dp(20)) {
                    endOfParent(ctx.dp(10))
                    centerVertically()
                },
            )
        }

    private val chip =
        GestureFrame(ctx).apply {
            add(layout, lParams(matchParent, matchParent))
            background = rippleDrawable(colors.hilitedCandidateBackColor)
        }

    override val root =
        constraintLayout {
            add(
                chip,
                lParams(matchConstraints, matchConstraints) {
                    startOfParent()
                    endOfParent()
                    centerVertically()
                    verticalMargin = ctx.dp(4)
                },
            )
        }

    fun setRecording() {
        micHighlighted = true
        applyMicTint()
        text.visibility = View.GONE
        level.visibility = View.VISIBLE
        showCloseButton()
        icon.setOnClickListener { onStop?.invoke() }
        chip.setOnClickListener { onStop?.invoke() }
    }

    /** A non-interactive status (preparing/recognising); tapping cancels. */
    fun setStatus(
        @StringRes textRes: Int,
    ) {
        micHighlighted = false
        applyMicTint()
        text.text = ctx.getString(textRes)
        text.visibility = View.VISIBLE
        level.visibility = View.GONE
        showCloseButton()
        icon.setOnClickListener { onDismiss?.invoke() }
        chip.setOnClickListener { onDismiss?.invoke() }
    }

    fun setResult(
        value: String,
        inserted: Boolean,
    ) {
        micHighlighted = false
        applyMicTint()
        text.text = value
        text.visibility = View.VISIBLE
        level.visibility = View.GONE
        icon.setOnClickListener { onStart?.invoke() }
        chip.setOnClickListener { if (!inserted) onInsert?.invoke() }
        if (inserted) {
            showUndoButton()
        } else {
            showCloseButton()
        }
    }

    /** Updates the level meter; 0..1, already smoothed by the caller. */
    fun setLevel(value: Float) {
        level.progress = (value * 100).toInt().coerceIn(0, 100)
    }

    /** Re-applies the colors that were set once at construction. */
    fun refreshColors() {
        text.setTextColor(colors.candidateTextColor)
        level.progressTintList = ColorStateList.valueOf(colors.hilitedCandidateTextColor)
        level.progressBackgroundTintList = ColorStateList.valueOf(levelTrackColor())
        applyMicTint()
        syncTrailingButton()
        chip.background = rippleDrawable(colors.hilitedCandidateBackColor)
    }

    /** The trailing button closes the pending result. */
    private fun showCloseButton() {
        showingUndo = false
        syncTrailingButton()
        trailing.setOnClickListener { onDismiss?.invoke() }
    }

    /** The trailing button undoes the insert, then falls back to close. */
    private fun showUndoButton() {
        showingUndo = true
        syncTrailingButton()
        trailing.setOnClickListener { onUndo?.invoke() }
    }

    private fun syncTrailingButton() {
        if (showingUndo) {
            trailing.setImageResource(R.drawable.ic_baseline_refresh_reversed_24)
            trailing.imageDrawable?.setTint(colors.hilitedCandidateTextColor)
        } else {
            trailing.setImageResource(R.drawable.ic_outline_cancel_24)
            trailing.imageDrawable?.setTint(colors.candidateTextColor)
        }
    }

    private fun applyMicTint() {
        val color = if (micHighlighted) VOICE_RECORDING_COLOR else colors.candidateTextColor
        icon.imageDrawable?.setTint(color)
    }

    private fun levelTrackColor(): Int = ColorUtils.setAlphaComponent(colors.candidateTextColor, LEVEL_TRACK_ALPHA)
}
