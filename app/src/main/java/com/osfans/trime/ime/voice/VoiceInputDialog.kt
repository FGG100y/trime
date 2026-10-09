/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ui.voice.VoicePermissionActivity
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import kotlin.math.log10

/**
 * How opaque the banner card is. Only the card background is made translucent;
 * the text and buttons on top stay fully opaque so they remain readable.
 */
private const val CARD_ALPHA = 0.8f

/** Corner radius of the pill buttons, in dp. */
private const val BUTTON_RADIUS_DP = 20

/** Alpha of the accent wash used as the primary button's fill on a light card. */
private const val PRIMARY_FILL_ALPHA = 0.2f

/** Card luminance above which the banner counts as "light". */
private const val LIGHT_CARD_LUMINANCE = 0.5

/** dB floor of the level meter: anything quieter than this reads as empty. */
private const val LEVEL_FLOOR_DB = 60f

/** Alpha of the level meter's track. */
private const val LEVEL_TRACK_ALPHA = 0.15f

/** Per-update release factor of the level meter; smaller falls back slower. */
private const val LEVEL_RELEASE = 0.25f

/**
 * Floating banner shown while the local voice input is recording and while the
 * inserted transcript can still be undone.
 *
 * The window is non-modal: it sits at the top of the screen, does not dim the
 * app and lets touches through, so the user can keep browsing the app while
 * dictating. States are driven by [VoiceInputManager].
 */
class VoiceInputDialog(
    override val di: DI,
    private val manager: VoiceInputManager,
) : DIAware {
    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val colors get() = scope.colors

    private var dialog: AlertDialog? = null
    private var stateJob: Job? = null
    private var amplitudeJob: Job? = null

    /** Smoothed 0..1 level, so the meter has a fast attack and a slow release. */
    private var smoothedLevel = 0f

    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var levelView: ProgressBar
    private lateinit var actionButton: Button
    private lateinit var undoButton: Button
    private lateinit var retryButton: Button
    private lateinit var cancelButton: Button

    private val density get() = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    /**
     * Maps the raw RMS onto 0..1 over a [LEVEL_FLOOR_DB] window. Raw RMS hugs
     * zero for anything but shouting, which is why the meter used to look like
     * a static line.
     */
    private fun toLevel(rms: Float): Float {
        if (rms <= 0f) return 0f
        val db = 20f * log10(rms)
        return ((db + LEVEL_FLOOR_DB) / LEVEL_FLOOR_DB).coerceIn(0f, 1f)
    }

    /** Instant attack, slow release, so the meter follows speech without flicker. */
    private fun smoothLevel(current: Float, target: Float): Float = if (target > current) target else current + (target - current) * LEVEL_RELEASE

    /** Scales what is left of [color]'s own alpha by [factor]. */
    private fun Int.withAlpha(factor: Float): Int = ColorUtils.setAlphaComponent(this, (Color.alpha(this) * factor).toInt().coerceIn(0, 255))

    fun show() {
        if (dialog?.isShowing == true) return

        val alertDialog =
            AlertDialog
                .Builder(context)
                .setView(buildContentView())
                .create()
        // The dialog chrome follows the IME theme, which may be dark while the
        // Rime colour scheme is light; draw the whole card ourselves instead.
        alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        alertDialog.setCanceledOnTouchOutside(false)
        dialog = alertDialog

        stateJob =
            service.lifecycleScope.launch {
                manager.state.collect { render(it) }
            }
        smoothedLevel = 0f
        amplitudeJob =
            service.lifecycleScope.launch {
                manager.amplitude.collect { rms ->
                    smoothedLevel = smoothLevel(smoothedLevel, toLevel(rms))
                    levelView.progress = (smoothedLevel * 100).toInt().coerceIn(0, 100)
                }
            }

        service.showDialog(
            alertDialog,
            floating = true,
            onDismiss = {
                cleanupJobs()
                manager.onDialogDismissed()
            },
        )
        manager.start()
    }

    fun dismiss() {
        cleanupJobs()
        dialog?.dismiss()
        dialog = null
    }

    /**
     * Cancels the collectors tied to the dialog. Called both when the dialog is
     * dismissed through [dismiss] and when it is dismissed from the outside, so
     * the jobs never outlive the view they render into.
     */
    private fun cleanupJobs() {
        stateJob?.cancel()
        stateJob = null
        amplitudeJob?.cancel()
        amplitudeJob = null
    }

    private fun buildContentView(): View {
        val cardColor = colors.keyboardBackColor.withAlpha(CARD_ALPHA)
        val cardBackground =
            GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(12).toFloat()
            }
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBackground
                setPadding(dp(20), dp(16), dp(20), dp(12))
            }

        val titleView =
            TextView(context).apply {
                text = context.getString(R.string.voice_input)
                setTextColor(colors.keyTextColor)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
        container.addView(titleView, matchWrap())

        statusView =
            TextView(context).apply {
                setTextColor(colors.keyTextColor)
                textSize = 16f
                gravity = Gravity.CENTER
            }
        container.addView(statusView, matchWrap())

        transcriptView =
            TextView(context).apply {
                setTextColor(colors.candidateTextColor)
                textSize = 18f
                gravity = Gravity.CENTER
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                visibility = View.GONE
            }
        container.addView(transcriptView, matchWrap())

        levelView =
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                visibility = View.GONE
                // The dialog theme's tints do not follow the Rime scheme, so
                // pick the colours here rather than let the meter blend into
                // the card.
                progressTintList = ColorStateList.valueOf(colors.keyTextColor)
                progressBackgroundTintList = ColorStateList.valueOf(colors.keyTextColor.withAlpha(LEVEL_TRACK_ALPHA))
            }
        container.addView(
            levelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8),
            ).apply { topMargin = dp(12) },
        )

        actionButton = button(R.string.voice_start, primary = true).apply {
            setOnClickListener { onActionClicked() }
        }
        undoButton = button(R.string.voice_undo).apply {
            setOnClickListener { manager.undo() }
        }
        retryButton = button(R.string.voice_retry).apply {
            setOnClickListener { manager.retry() }
        }
        cancelButton = button(R.string.voice_cancel).apply {
            setOnClickListener { manager.cancel() }
        }

        val buttonRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            }
        listOf(actionButton, undoButton, retryButton, cancelButton).forEach {
            buttonRow.addView(it, wrapWrap())
        }
        container.addView(buttonRow, matchWrap())

        return container
    }

    /**
     * A pill button with a ripple. The primary action carries the theme's
     * highlighted key colour, the secondary ones the normal key colour. The
     * default background, minimum sizes and elevation animator are dropped so
     * the shape and the feedback are ours.
     */
    private fun button(textRes: Int, primary: Boolean = false): Button {
        val accent = colors.hilitedKeyBackColor
        // A solid accent is right on a dark card, where a wash would disappear;
        // on a light card the wash is what keeps the button from turning into a
        // dark block.
        val darkCard = ColorUtils.calculateLuminance(colors.keyboardBackColor) < LIGHT_CARD_LUMINANCE
        val fillColor =
            when {
                !primary -> colors.keyBackColor
                darkCard -> accent
                else -> accent.withAlpha(PRIMARY_FILL_ALPHA)
            }
        val contentColor = if (primary && darkCard) colors.hilitedKeyTextColor else colors.keyTextColor
        val radius = dp(BUTTON_RADIUS_DP).toFloat()

        val shape =
            GradientDrawable().apply {
                setColor(fillColor)
                cornerRadius = radius
            }
        val background =
            RippleDrawable(
                ColorStateList.valueOf(contentColor.withAlpha(0.16f)),
                shape,
                // The mask clips the ripple to the rounded shape.
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = radius
                },
            )

        return Button(context).apply {
            setText(textRes)
            this.background = background
            // Button inherits a background tint from the dialog theme
            // (Theme_DeviceDefault_Settings). RippleDrawable extends
            // LayerDrawable, whose setTintList pushes the tint down to every
            // layer, so our fill would be replaced by the theme's button
            // colour. Clear it to keep the theme's key colours.
            backgroundTintList = null
            setTextColor(contentColor)
            isAllCaps = false
            stateListAnimator = null
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(20), dp(9), dp(20), dp(9))
            textSize = 14f
            letterSpacing = 0.02f
            includeFontPadding = false
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    private fun onActionClicked() {
        when (manager.state.value) {
            is VoiceUiState.Recording -> manager.stopAndRecognize()

            is VoiceUiState.Failure ->
                when {
                    !manager.hasPermission() -> launchPermissionActivity()
                    manager.selectedModel() == null -> AppUtils.launchMainActivity(service)
                    else -> manager.start()
                }

            else -> manager.start()
        }
    }

    private fun render(state: VoiceUiState) {
        transcriptView.visibility = View.GONE
        undoButton.visibility = View.GONE
        retryButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
        actionButton.visibility = View.GONE
        levelView.visibility = View.GONE

        when (state) {
            is VoiceUiState.Idle -> {
                statusView.text = ""
                actionButton.visibility = View.VISIBLE
            }

            is VoiceUiState.Loading -> {
                statusView.text = context.getString(R.string.voice_recognizing)
            }

            is VoiceUiState.Recording -> {
                statusView.text = context.getString(R.string.voice_recording)
                levelView.visibility = View.VISIBLE
                actionButton.setText(R.string.voice_stop)
                actionButton.visibility = View.VISIBLE
            }

            is VoiceUiState.Recognizing -> {
                statusView.text = context.getString(R.string.voice_recognizing)
            }

            is VoiceUiState.Committed -> {
                statusView.text = context.getString(R.string.voice_inserted)
                transcriptView.text = state.text
                transcriptView.visibility = View.VISIBLE
                undoButton.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE
            }

            is VoiceUiState.Failure -> {
                statusView.text = state.message
                actionButton.setText(
                    when {
                        !manager.hasPermission() -> R.string.voice_grant_permission
                        manager.selectedModel() == null -> R.string.voice_open_settings
                        else -> R.string.voice_retry
                    },
                )
                actionButton.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE
            }
        }
    }

    private fun launchPermissionActivity() {
        val intent =
            Intent(context, VoicePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun wrapWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(6)
        marginEnd = dp(6)
    }
}
