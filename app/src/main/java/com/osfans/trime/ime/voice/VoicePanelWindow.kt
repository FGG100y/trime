/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Intent
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
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.voice.VoicePermissionActivity
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance

/**
 * The panel shown in place of the keyboard while recording and while a
 * transcription is waiting to be confirmed.
 *
 * States are driven by [VoiceInputManager].
 */
class VoicePanelWindow(override val di: DI) :
    BoardWindow.BarBoardWindow(di),
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by instance()
    private val manager: VoiceInputManager by instance()
    private val windowManager: BoardWindowManager by instance()
    private val scope: ThemeScope by instance()
    private val colors get() = scope.colors

    override val title: String get() = context.getString(R.string.voice_input)

    private lateinit var statusView: TextView
    private lateinit var pendingView: TextView
    private lateinit var levelView: ProgressBar
    private lateinit var actionButton: Button
    private lateinit var confirmButton: Button
    private lateinit var retryButton: Button
    private lateinit var cancelButton: Button

    private var stateJob: Job? = null
    private var amplitudeJob: Job? = null

    private val density get() = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun button(textRes: Int): Button = Button(context).apply {
        setText(textRes)
        setBackgroundColor(colors.keyBackColor)
        setTextColor(colors.keyTextColor)
    }

    override fun onCreateView(): View {
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(16), dp(12), dp(16))
                setBackgroundColor(colors.backColor)
            }

        statusView =
            TextView(context).apply {
                setTextColor(colors.keyTextColor)
                textSize = 16f
                gravity = Gravity.CENTER
            }
        container.addView(statusView, matchWrap())

        pendingView =
            TextView(context).apply {
                setTextColor(colors.hilitedCandidateTextColor)
                textSize = 20f
                gravity = Gravity.CENTER
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

        val buttonRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

        actionButton = button(R.string.voice_start).apply {
            setOnClickListener { onActionClicked() }
        }
        confirmButton = button(R.string.voice_confirm).apply {
            setOnClickListener {
                manager.confirm()
                backToKeyboard()
            }
        }
        retryButton = button(R.string.voice_retry).apply {
            setOnClickListener { manager.retry() }
        }
        cancelButton = button(R.string.voice_cancel).apply {
            setOnClickListener {
                manager.cancel()
                backToKeyboard()
            }
        }

        listOf(actionButton, confirmButton, retryButton, cancelButton).forEach {
            buttonRow.addView(it, wrapWrap())
        }
        container.addView(buttonRow, matchWrap())

        return container
    }

    override fun onAttached() {
        stateJob = service.lifecycleScope.launch {
            manager.state.collect { render(it) }
        }
        amplitudeJob = service.lifecycleScope.launch {
            manager.amplitude.collect { value ->
                levelView.progress = (value * 100).toInt().coerceIn(0, 100)
            }
        }

        manager.start()
        if (!manager.hasPermission()) launchPermissionActivity()
    }

    override fun onDetached() {
        stateJob?.cancel()
        amplitudeJob?.cancel()
        manager.close()
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

    private fun backToKeyboard() {
        windowManager.attachWindow(KeyboardWindow)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun wrapWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }
}
