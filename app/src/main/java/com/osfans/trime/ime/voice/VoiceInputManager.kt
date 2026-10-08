/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.Manifest
import android.content.pm.PackageManager
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.voice.AsrEngine
import com.osfans.trime.data.voice.SherpaOnnxEngine
import com.osfans.trime.data.voice.VoiceModel
import com.osfans.trime.data.voice.VoiceModelManager
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import timber.log.Timber

/** UI state of the local voice input panel. */
sealed interface VoiceUiState {
    /** Nothing is happening. */
    data object Idle : VoiceUiState

    /** The recognizer is being loaded (the first use of a model can take a while). */
    data object Loading : VoiceUiState

    /** The microphone is capturing. */
    data object Recording : VoiceUiState

    /** Audio is being transcribed. */
    data object Recognizing : VoiceUiState

    /** A transcription is waiting for the user to confirm it. */
    data class Pending(val text: String) : VoiceUiState

    /** Something went wrong; [message] is already localised. */
    data class Failure(val message: String) : VoiceUiState
}

/**
 * Drives the local (fully on-device) voice input flow:
 * record -> transcribe -> show the text as a composing string in the editor
 * until the user confirms or cancels it.
 */
class VoiceInputManager(override val di: DI) : DIAware {
    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()
    private val windowManager: BoardWindowManager by instance()

    private val prefs = AppPrefs.defaultInstance()

    private val engine: AsrEngine by lazy { SherpaOnnxEngine() }
    private val recorder = AudioRecorder(engine.sampleRate)

    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    val amplitude: StateFlow<Float> get() = recorder.amplitude

    private var job: Job? = null

    /** Bumped on every new session so stale background results are ignored. */
    private var generation = 0

    /** Text currently shown as a composing string, waiting for confirmation. */
    private var pendingText: String = ""

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    fun selectedModel(): VoiceModel? = VoiceModelManager.findModel(prefs.voice.modelId.getValue())

    /** Whether the on-device recogniser can run on this device. */
    val isSupported: Boolean get() = engine.isSupported

    /** Attaches the voice panel to the keyboard area. */
    fun open() {
        windowManager.attachWindow(VoicePanelWindow(di))
    }

    /** Starts a new recording session, loading the recognizer if needed. */
    fun start() {
        if (recorder.isRecording) return

        if (!isSupported) {
            fail(R.string.voice_unsupported_abi)
            return
        }

        if (!hasPermission()) {
            fail(R.string.voice_permission_needed)
            return
        }
        val model =
            selectedModel() ?: run {
                fail(R.string.voice_no_model)
                return
            }

        clearPendingComposing()
        val current = ++generation
        _state.value = VoiceUiState.Loading

        job?.cancel()
        job =
            service.lifecycleScope.launch {
                val language = prefs.voice.language.getValue().name.lowercase()
                val loaded =
                    withContext(Dispatchers.Default) {
                        runCatching { engine.load(model, language) }
                    }
                if (current != generation) return@launch
                loaded.onFailure {
                    Timber.e(it, "Failed to load voice model")
                    fail(R.string.voice_recognition_failed, it.message.orEmpty())
                    return@launch
                }

                if (recorder.start()) {
                    _state.value = VoiceUiState.Recording
                } else {
                    fail(R.string.voice_recognition_failed, "AudioRecord init failed")
                }
            }
    }

    /** Stops the capture and transcribes what was recorded. */
    fun stopAndRecognize() {
        if (!recorder.isRecording) return

        val samples = recorder.stop()
        if (samples.size < engine.sampleRate / 4) {
            fail(R.string.voice_empty_result)
            return
        }

        val current = ++generation
        _state.value = VoiceUiState.Recognizing
        job =
            service.lifecycleScope.launch {
                val result =
                    withContext(Dispatchers.Default) {
                        runCatching { engine.recognize(samples) }
                    }
                if (current != generation) return@launch
                result
                    .onSuccess { text ->
                        if (text.isBlank()) {
                            fail(R.string.voice_empty_result)
                        } else {
                            pendingText = text
                            // Put the recognised text into the editor as a composing
                            // string, so the user can confirm it before it is committed.
                            service.updateComposingText(text)
                            _state.value = VoiceUiState.Pending(text)
                        }
                    }.onFailure {
                        Timber.e(it, "Voice recognition failed")
                        fail(R.string.voice_recognition_failed, it.message.orEmpty())
                    }
            }
    }

    /** Commits the pending transcription. */
    fun confirm() {
        val text = pendingText
        pendingText = ""
        if (text.isNotEmpty()) {
            // commitText finishes the composing text as-is when it equals the
            // composing string, so the text stays in the editor untouched.
            service.commitText(text)
        }
        close()
    }

    /** Discards the pending transcription and clears the composing string. */
    fun cancel() {
        clearPendingComposing()
        close()
    }

    /** Discards the current take and starts a new one. */
    fun retry() {
        clearPendingComposing()
        close()
        start()
    }

    /** Stops recording/recognising and resets the state. */
    fun close() {
        generation++
        job?.cancel()
        job = null
        recorder.cancel()
        clearPendingComposing()
        _state.value = VoiceUiState.Idle
    }

    /** Releases the recognizer, e.g. when the input method is destroyed. */
    fun release() {
        close()
        engine.release()
    }

    private fun clearPendingComposing() {
        if (pendingText.isNotEmpty()) {
            service.updateComposingText("")
            pendingText = ""
        }
    }

    private fun fail(
        stringRes: Int,
        vararg args: Any,
    ) {
        _state.value = VoiceUiState.Failure(context.getString(stringRes, *args))
    }
}
