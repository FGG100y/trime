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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** The transcript was inserted; [text] can still be undone for a moment. */
    data class Committed(val text: String) : VoiceUiState

    /** Something went wrong; [message] is already localised. */
    data class Failure(val message: String) : VoiceUiState
}

/**
 * Drives the local (fully on-device) voice input flow:
 * record -> transcribe -> insert the text straight into the editor, with a
 * short window in which the insertion can still be undone or re-recorded.
 *
 * The transcript is committed, never kept as a composing string: Rime owns the
 * composing text, so sharing it with voice input is the fragile part the old
 * confirm step relied on.
 */
class VoiceInputManager(override val di: DI) : DIAware {
    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()

    private val prefs = AppPrefs.defaultInstance()

    private val engine: AsrEngine by lazy { SherpaOnnxEngine() }
    private val recorder = AudioRecorder(engine.sampleRate)

    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    val amplitude: StateFlow<Float> get() = recorder.amplitude

    private var job: Job? = null
    private var autoHideJob: Job? = null
    private var maxDurationJob: Job? = null
    private var inputDialog: VoiceInputDialog? = null

    /** Bumped on every new session so stale background results are ignored. */
    private var generation = 0

    /** Text inserted by the last session, kept only while it can still be undone. */
    private var committedText: String = ""

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    fun selectedModel(): VoiceModel? = VoiceModelManager.findModel(prefs.voice.modelId.getValue())

    /** Whether the on-device recogniser can run on this device. */
    val isSupported: Boolean get() = engine.isSupported

    /** Shows the floating voice input dialog. */
    fun open() {
        val dialog = inputDialog ?: VoiceInputDialog(di, this).also { inputDialog = it }
        dialog.show()
    }

    /**
     * Called when the banner was dismissed without going through [close], for
     * example when another dialog replaced it. Releases the session so the
     * microphone is not left capturing in the background.
     */
    fun onDialogDismissed() {
        releaseSession()
        inputDialog = null
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
                    maxDurationJob?.cancel()
                    maxDurationJob =
                        service.lifecycleScope.launch {
                            delay(MAX_RECORDING_MS)
                            if (current == generation && recorder.isRecording) stopAndRecognize()
                        }
                } else {
                    fail(R.string.voice_recognition_failed, "AudioRecord init failed")
                }
            }
    }

    /** Stops the capture and transcribes what was recorded. */
    fun stopAndRecognize() {
        if (!recorder.isRecording) return

        maxDurationJob?.cancel()
        maxDurationJob = null
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
                            insertAndOfferUndo(text, current)
                        }
                    }.onFailure {
                        Timber.e(it, "Voice recognition failed")
                        fail(R.string.voice_recognition_failed, it.message.orEmpty())
                    }
            }
    }

    /**
     * Inserts [text] straight into the editor and keeps the card around for a
     * moment so it can be undone or re-recorded.
     */
    private fun insertAndOfferUndo(text: String, current: Int) {
        service.commitText(text)
        committedText = text
        _state.value = VoiceUiState.Committed(text)
        autoHideJob?.cancel()
        autoHideJob =
            service.lifecycleScope.launch {
                delay(COMMIT_UNDO_WINDOW_MS)
                if (current == generation) dismissCard()
            }
    }

    /** Removes the transcript inserted by the last session, when it is still there. */
    fun undo() {
        val text = committedText
        committedText = ""
        if (text.isNotEmpty()) {
            service.deleteCommittedTextIfPresent(text)
        }
        close()
    }

    /** Discards the session and closes the dialog. */
    fun cancel() {
        close()
    }

    /**
     * Drops the last transcript and starts a new take, so the new one replaces
     * it instead of being appended.
     */
    fun retry() {
        val text = committedText
        committedText = ""
        if (text.isNotEmpty()) {
            service.deleteCommittedTextIfPresent(text)
        }
        releaseSession()
        start()
    }

    /** Stops recording/recognising, resets the state and closes the dialog. */
    fun close() {
        releaseSession()
        inputDialog?.dismiss()
        inputDialog = null
    }

    /** Resets the session without touching the dialog. */
    private fun releaseSession() {
        generation++
        job?.cancel()
        job = null
        autoHideJob?.cancel()
        autoHideJob = null
        maxDurationJob?.cancel()
        maxDurationJob = null
        recorder.cancel()
        committedText = ""
        _state.value = VoiceUiState.Idle
    }

    /** Hides the card, leaving the inserted text in the editor. */
    private fun dismissCard() {
        autoHideJob = null
        committedText = ""
        _state.value = VoiceUiState.Idle
        inputDialog?.dismiss()
        inputDialog = null
    }

    /** Releases the recognizer, e.g. when the input method is destroyed. */
    fun release() {
        close()
        engine.release()
    }

    private fun fail(
        stringRes: Int,
        vararg args: Any,
    ) {
        _state.value = VoiceUiState.Failure(context.getString(stringRes, *args))
    }

    private companion object {
        /** How long the inserted transcript can still be undone. */
        const val COMMIT_UNDO_WINDOW_MS = 6_000L

        /** Longest single recording; mirrors the recorder's own hard cap. */
        const val MAX_RECORDING_MS = 60_000L
    }
}
