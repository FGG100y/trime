/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.Manifest
import android.content.Intent
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
import com.osfans.trime.ui.voice.VoicePermissionActivity
import com.osfans.trime.util.AppUtils
import com.osfans.trime.util.toast
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

/** Green used for the microphone while the recorder is capturing. */
val VOICE_RECORDING_COLOR: Int = 0xFF4CAF50.toInt()

/** UI state of the local voice input session shown in the input bar. */
sealed interface VoiceUiState {
    /** Nothing is happening. */
    data object Idle : VoiceUiState

    /** The recognizer is being loaded (the first use of a model can take a while). */
    data object Preparing : VoiceUiState

    /** The microphone is capturing. */
    data object Recording : VoiceUiState

    /** Audio is being transcribed. */
    data object Recognizing : VoiceUiState

    /**
     * The transcript is held in the input bar. [inserted] is true once it has
     * been put into the editor, which turns the chip's action into an undo.
     */
    data class Result(
        val text: String,
        val inserted: Boolean,
    ) : VoiceUiState
}

/**
 * Drives the local (fully on-device) voice input flow:
 * record -> transcribe -> hold the transcript in the input bar, where the user
 * inserts it at the cursor, or discards it.
 *
 * The transcript is **never inserted automatically**: inserting is a deliberate
 * tap, so it lands where the user wants and there is no fragile "undo an
 * automatic insertion" path. Undo only ever backs out a deliberate insert, and
 * only while the text can still be located.
 */
class VoiceInputManager(override val di: DI) : DIAware {
    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()

    private val prefs = AppPrefs.defaultInstance()

    private val engine: AsrEngine by lazy { SherpaOnnxEngine() }
    private val recorder = AudioRecorder(engine.sampleRate)

    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    /** Raw microphone RMS of the current recording, for the bar's level meter. */
    val amplitude: StateFlow<Float> get() = recorder.amplitude

    private var job: Job? = null
    private var maxDurationJob: Job? = null

    /** Bumped on every new session so stale background results are ignored. */
    private var generation = 0

    /** The transcript currently held in the bar. */
    private var resultText = ""

    /** Absolute range the last insert landed in, used to undo right away. */
    private var insertedRange: IntRange? = null

    /** Caret position the insert is expected to leave behind, -1 when unknown. */
    private var insertedCaret = -1

    /** Whether the caret produced by the insert itself has been observed yet. */
    private var insertSettled = false

    /** Backstop that drops an inserted transcript the user never acts on. */
    private var insertTimeoutJob: Job? = null

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    fun selectedModel(): VoiceModel? = VoiceModelManager.findModel(prefs.voice.modelId.getValue())

    /** Whether the on-device recogniser can run on this device. */
    val isSupported: Boolean get() = engine.isSupported

    /**
     * Entry point of the space bar's long press. Missing setup is reported with
     * a toast and the matching screen is opened directly, instead of the
     * blocking dialog that used to host those actions.
     */
    fun open() {
        when {
            !isSupported -> service.toast(R.string.voice_unsupported_abi)

            !hasPermission() -> {
                launchPermissionActivity()
                service.toast(R.string.voice_permission_needed)
            }

            selectedModel() == null -> {
                AppUtils.launchMainToVoice(service)
                service.toast(R.string.voice_no_model)
            }

            else -> start()
        }
    }

    /** Starts a new recording session, loading the recognizer if needed. */
    fun start() {
        if (recorder.isRecording) return

        if (!isSupported) {
            service.toast(R.string.voice_unsupported_abi)
            return
        }
        if (!hasPermission()) {
            launchPermissionActivity()
            service.toast(R.string.voice_permission_needed)
            return
        }
        val model =
            selectedModel() ?: run {
                AppUtils.launchMainToVoice(service)
                service.toast(R.string.voice_no_model)
                return
            }

        val current = ++generation
        // A new recording replaces anything still held from the previous one.
        resultText = ""
        insertedRange = null
        cancelInsertTimeout()
        insertedCaret = -1
        insertSettled = false
        _state.value = VoiceUiState.Preparing

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
                            resultText = text
                            insertedRange = null
                            _state.value = VoiceUiState.Result(text, inserted = false)
                        }
                    }.onFailure {
                        Timber.e(it, "Voice recognition failed")
                        fail(R.string.voice_recognition_failed, it.message.orEmpty())
                    }
            }
    }

    /** Inserts the held transcript at the current cursor. */
    fun insertResult() {
        val text = resultText
        if (text.isEmpty()) return
        val offset = service.lastCursorOffset
        // End any active Rime composition first, so the transcript is not mixed
        // into the composing region the user is still typing into.
        service.finishComposingTextForVoice()
        service.commitText(text)
        insertedRange = if (offset >= 0) offset until (offset + text.length) else null
        insertedCaret = if (offset >= 0) offset + text.length else -1
        insertSettled = false
        armInsertTimeout()
        _state.value = VoiceUiState.Result(text, inserted = true)
    }

    /** Removes the last insert, when it can still be located. */
    fun undoInsert() {
        val text = resultText
        if (text.isEmpty()) return
        if (service.deleteVoiceTextIfPresent(text, insertedRange)) {
            insertedRange = null
            cancelInsertTimeout()
            insertedCaret = -1
            insertSettled = false
            _state.value = VoiceUiState.Result(text, inserted = false)
        } else {
            // The insert is no longer where we left it, so it can never be
            // undone: say so, then end the session instead of leaving a chip
            // that can neither be inserted again nor safely undone.
            service.toast(R.string.voice_undo_failed)
            close()
        }
    }

    /**
     * Reports an editor selection change.
     *
     * While the transcript is pending (not inserted) this is ignored, because
     * the user is free to place the cursor before inserting. Once inserted, the
     * undo window only lasts as long as the caret stays right after the insert:
     * undoing after the caret moved would delete text that may be off screen,
     * silently, and the chip could not be inserted again anyway. So the first
     * caret change that is not the insert's own finalises it and closes the
     * session.
     */
    fun onSelectionUpdate(
        start: Int,
        end: Int,
    ) {
        val current = _state.value
        if (current !is VoiceUiState.Result || !current.inserted) return
        val caret = if (start == end) start else -1
        if (!insertSettled) {
            // The commit moves the caret here, sometimes through more than one
            // update; don't judge the selection until it lands on the insert
            // point, otherwise the insert itself would close the session.
            if (caret >= 0 && (insertedCaret < 0 || caret == insertedCaret)) {
                insertedCaret = caret
                insertSettled = true
            }
            return
        }
        if (caret >= 0 && caret == insertedCaret) return
        close()
    }

    /** The editor changed; an inserted transcript is final and can be dropped. */
    fun onEditorStarted() {
        val current = _state.value
        if (current is VoiceUiState.Result && current.inserted) close()
    }

    /** Auto-finalises an inserted result the user never interacts with. */
    private fun armInsertTimeout() {
        insertTimeoutJob?.cancel()
        insertTimeoutJob =
            service.lifecycleScope.launch {
                delay(INSERT_FINALIZE_MS)
                insertTimeoutJob = null
                val current = _state.value
                if (current is VoiceUiState.Result && current.inserted) close()
            }
    }

    private fun cancelInsertTimeout() {
        insertTimeoutJob?.cancel()
        insertTimeoutJob = null
    }

    /** Closes the session and drops the held transcript. */
    fun close() {
        releaseSession()
    }

    /** Resets the session and hides the bar. */
    private fun releaseSession() {
        generation++
        job?.cancel()
        job = null
        maxDurationJob?.cancel()
        maxDurationJob = null
        recorder.cancel()
        cancelInsertTimeout()
        resultText = ""
        insertedRange = null
        insertedCaret = -1
        insertSettled = false
        _state.value = VoiceUiState.Idle
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
        service.toast(context.getString(stringRes, *args))
        releaseSession()
    }

    private fun launchPermissionActivity() {
        context.startActivity(
            Intent(context, VoicePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private companion object {
        /** Longest single recording; mirrors the recorder's own hard cap. */
        const val MAX_RECORDING_MS = 60_000L

        /** How long an inserted transcript may sit in the bar before it is dropped. */
        const val INSERT_FINALIZE_MS = 8_000L
    }
}
