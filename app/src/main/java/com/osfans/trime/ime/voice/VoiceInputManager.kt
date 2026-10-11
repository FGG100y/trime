/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
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
import kotlinx.coroutines.channels.Channel
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

    /** The microphone is capturing; finished utterances are inserted as they come. */
    data object Recording : VoiceUiState

    /** The last buffered utterance is being transcribed after the capture stopped. */
    data object Recognizing : VoiceUiState
}

/**
 * Drives the local (fully on-device) voice input flow.
 *
 * The recognizer is an offline (whole-utterance) model, so it cannot stream
 * partial text. To still let the user watch the result appear, the capture is
 * cut into utterances at natural pauses ([VoiceSegmenter]) and each finished
 * utterance is transcribed and inserted **immediately**, while the user is
 * still speaking. That is the "commit on pause" behaviour described in
 * `doc/voice-input.md`.
 *
 * The committed text is ordinary editor text: there is no undo, and no held
 * result. The session ends when the user taps the microphone, when 30 s pass
 * with no speech, or as soon as the user touches the keyboard to type, switch
 * layout or open a panel — at that point they have moved on to manual input.
 */
class VoiceInputManager(override val di: DI) : DIAware {
    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()

    private val prefs = AppPrefs.defaultInstance()

    private val engine: AsrEngine by lazy { SherpaOnnxEngine() }
    private val recorder = AudioRecorder(engine.sampleRate)

    // Prefer the bundled Silero VAD; fall back to the level-based one only if
    // the model cannot be loaded on this device.
    private val segmenter: VoiceSegmenter by lazy {
        runCatching { SileroVadSegmenter(context.assets, engine.sampleRate) }
            .onFailure { Timber.w(it, "Silero VAD unavailable, falling back to the energy segmenter") }
            .getOrElse { EnergySegmenter(engine.sampleRate) }
    }

    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    /** Raw microphone RMS of the current recording, for the bar's level meter. */
    val amplitude: StateFlow<Float> get() = recorder.amplitude

    private var job: Job? = null
    private var consumerJob: Job? = null

    /** Bumped on every new session so stale background results are ignored. */
    private var generation = 0

    /** Utterances waiting to be transcribed, recreated for every session. */
    private var segments = Channel<FloatArray>(Channel.UNLIMITED)

    /** Last time speech was seen, for the silence auto-stop; capture thread only. */
    private var lastSpeechAt = 0L

    /** Whether any utterance was committed in this session. Main thread only. */
    private var committedAny = false

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
        committedAny = false
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

                segments = Channel(Channel.UNLIMITED)
                consumerJob = launchConsumer(current)
                lastSpeechAt = SystemClock.elapsedRealtime()

                val started =
                    recorder.start { block ->
                        segmenter.accept(block).forEach { segments.trySend(it) }
                        val now = SystemClock.elapsedRealtime()
                        if (segmenter.isSpeechDetected) {
                            lastSpeechAt = now
                        } else if (now - lastSpeechAt >= SILENCE_STOP_MS) {
                            // Push the threshold out so the stop is asked for once.
                            lastSpeechAt = now
                            service.lifecycleScope.launch {
                                if (current == generation && recorder.isRecording) stop()
                            }
                        }
                    }
                if (current != generation) {
                    recorder.cancel()
                    return@launch
                }
                if (started) {
                    _state.value = VoiceUiState.Recording
                } else {
                    fail(R.string.voice_recognition_failed, "AudioRecord init failed")
                }
            }
    }

    /**
     * Transcribes finished utterances in order and inserts them as they arrive.
     *
     * The engine is not thread safe, so a single consumer handles every
     * segment; recording keeps going while it works, and the channel queue
     * absorbs utterances the recognizer has not caught up with yet.
     */
    private fun launchConsumer(current: Int): Job = service.lifecycleScope.launch(Dispatchers.Default) {
        for (samples in segments) {
            if (current != generation) break
            val text =
                runCatching { engine.recognize(samples) }
                    .onFailure { Timber.e(it, "Voice recognition failed") }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
            if (text.isEmpty()) continue
            withContext(Dispatchers.Main) {
                if (current != generation) return@withContext
                commitSegment(text)
            }
        }
    }

    /** Inserts one finished utterance at the cursor. */
    private fun commitSegment(text: String) {
        // End any active Rime composition first, so the transcript is not mixed
        // into the composing region the user is still typing into.
        service.finishComposingTextForVoice()
        service.commitText(text)
        committedAny = true
    }

    /** Ends the capture, transcribes whatever is still buffered, then hides the bar. */
    fun stop() {
        if (!recorder.isRecording) return

        recorder.stop()
        segmenter.flush().forEach { segments.trySend(it) }
        segments.close()

        val current = generation
        val consumer = consumerJob
        consumerJob = null
        _state.value = VoiceUiState.Recognizing

        service.lifecycleScope.launch {
            consumer?.join()
            if (current != generation) return@launch
            if (!committedAny) service.toast(R.string.voice_empty_result)
            releaseSession()
        }
    }

    /**
     * The user touched the keyboard: typed, switched layout or opened a panel.
     * They have moved on to manual input, so the voice session ends.
     */
    fun onManualInput() {
        if (_state.value is VoiceUiState.Idle) return
        close()
    }

    /** Closes the session and hides the bar. */
    fun close() {
        releaseSession()
    }

    /** Releases the recognizer, e.g. when the input method is destroyed. */
    fun release() {
        close()
        segmenter.release()
        engine.release()
    }

    private fun releaseSession() {
        generation++
        job?.cancel()
        job = null
        consumerJob?.cancel()
        consumerJob = null
        segments.close()
        recorder.cancel()
        segmenter.reset()
        _state.value = VoiceUiState.Idle
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
        /** Silence after which the session ends on its own, freeing the mic. */
        const val SILENCE_STOP_MS = 30_000L
    }
}
