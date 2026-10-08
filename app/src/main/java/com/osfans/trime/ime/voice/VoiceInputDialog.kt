/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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

/**
 * Floating dialog shown while the local voice input is recording and while a
 * transcription is waiting to be confirmed.
 *
 * Unlike the keyboard-free panel it replaces, the app and the keyboard stay
 * visible and only the transcript pops up. States are driven by
 * [VoiceInputManager].
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

    private lateinit var statusView: TextView
    private lateinit var pendingView: TextView
    private lateinit var levelView: ProgressBar
    private lateinit var actionButton: Button
    private lateinit var confirmButton: Button
    private lateinit var retryButton: Button
    private lateinit var cancelButton: Button

    private val density get() = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

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
        alertDialog.setOnCancelListener { manager.onDialogCancelled() }
        dialog = alertDialog

        stateJob =
            service.lifecycleScope.launch {
                manager.state.collect { render(it) }
            }
        amplitudeJob =
            service.lifecycleScope.launch {
                manager.amplitude.collect { value ->
                    levelView.progress = (value * 100).toInt().coerceIn(0, 100)
                }
            }

        service.showDialog(alertDialog)
        manager.start()
    }

    fun dismiss() {
        stateJob?.cancel()
        stateJob = null
        amplitudeJob?.cancel()
        amplitudeJob = null
        dialog?.dismiss()
        dialog = null
    }

    private fun buildContentView(): View {
        val cardColor = colors.keyboardBackColor
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

        pendingView =
            TextView(context).apply {
                setTextColor(colors.candidateTextColor)
                textSize = 20f
                gravity = Gravity.CENTER
                setTextIsSelectable(true)
                visibility = View.GONE
            }
        container.addView(pendingView, matchWrap())

        levelView =
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                visibility = View.GONE
            }
        container.addView(
            levelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8),
            ).apply { topMargin = dp(12) },
        )

        actionButton = button(R.string.voice_start).apply {
            setOnClickListener { onActionClicked() }
        }
        confirmButton = button(R.string.voice_confirm).apply {
            setOnClickListener { manager.confirm() }
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
        listOf(actionButton, confirmButton, retryButton, cancelButton).forEach {
            buttonRow.addView(it, wrapWrap())
        }
        container.addView(buttonRow, matchWrap())

        return container
    }

    private fun button(textRes: Int): Button = Button(context).apply {
        setText(textRes)
        setBackgroundColor(colors.keyBackColor)
        setTextColor(colors.keyTextColor)
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
        pendingView.visibility = View.GONE
        confirmButton.visibility = View.GONE
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

            is VoiceUiState.Pending -> {
                statusView.text = ""
                pendingView.text = state.text
                pendingView.visibility = View.VISIBLE
                confirmButton.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE
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
        marginStart = dp(4)
        marginEnd = dp(4)
    }
}
