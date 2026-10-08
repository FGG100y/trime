# Local (on-device) voice input

Design and implementation notes for Trime's optional, fully offline voice input.

## Goals

- Recognise speech **entirely on device**; audio never leaves the phone.
- The app keeps its current privacy property of **not requesting `INTERNET`**.
- Plug into the existing input flow: long press space, record, review the text in
  the editor, then confirm or cancel.

## Non-goals (v1)

- No cloud fallback, no account, no telemetry.
- No streaming / live word-by-word output: the user records one utterance at a
  time, and the whole utterance is transcribed when recording stops.
- The existing "system voice input method" behaviour is kept as an option, it is
  not replaced.

## Decisions

| Topic | Decision |
| --- | --- |
| Engine | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) offline recognizers |
| Model distribution | Manual import through the system file picker. No in-app download, so `INTERNET` stays out of the manifest |
| ABIs | `arm64-v8a` (x86_64 optional, for emulators) |
| Native library | Prebuilt `.so` from the official sherpa-onnx AAR, for a first working version |
| Trigger | The space key's long press, selectable in Settings |
| Result | Shown in the editor as a composing string until confirmed |
| Streaming | Not used |
| VAD | Not used in v1 (Silero VAD can be added later) |

## Why prebuilt `.so`, and what it costs

Trime builds every native dependency from source (see `.gitmodules` and
`app/src/main/jni/CMakeLists.txt`), which is what lets F-Droid reproduce the
build. sherpa-onnx has no Maven Central artifact and, on Android, is normally
consumed as prebuilt binaries. For the first iteration we therefore vendor the
prebuilt library:

```
script/fetch-sherpa-onnx.sh        # downloads the AAR and extracts the native libs
app/libs/sherpa-onnx.jar           # the Java/Kotlin API (committed, ~240 KB)
app/src/main/jniLibs/arm64-v8a/    # the native libraries (fetched, ~30 MB, gitignored)
```

The small API jar is committed so a clean checkout compiles; the large native
libraries are **not** committed and must be fetched once:

```sh
script/fetch-sherpa-onnx.sh                 # arm64-v8a only
script/fetch-sherpa-onnx.sh arm64-v8a x86_64
```

Consequences:

- Building the prebuilt library from source (and onnxruntime with it) is the
  path to F-Droid reproducibility; that is deliberate future work.
- Until then, a build flavour that omits voice input can be used for F-Droid, or
  the project can decide to accept prebuilt binaries.

## Models

Models are imported by the user into `filesDir/voice/models/<id>/`, together with
a generated `voice-model.json` manifest. Supported sherpa-onnx offline layouts:

| Type | Files | Notes |
| --- | --- | --- |
| SenseVoice | `model.int8.onnx` + `tokens.txt` | **Recommended**: good Chinese, fast, multilingual |
| Paraformer | `model.int8.onnx` + `tokens.txt` | Chinese |
| Whisper | `*-encoder.onnx` + `*-decoder.onnx` + `tokens.txt` | Multilingual, larger/slower |
| Transducer | `encoder*.onnx` + `decoder*.onnx` + `joiner*.onnx` + `tokens.txt` | `zipformer` etc. |
| Zipformer CTC | `model*.onnx` + `tokens.txt` | |

A good starting point is
`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09` (about 160 MB compressed,
~230 MB on disk) from the
[sherpa-onnx model releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models).
Unzip it somewhere, then import that folder from *Settings → Local voice input →
Import model*.

### Baseline

- `arm64-v8a`, Android 8+ for the voice feature, ~230 MB free storage for a
  SenseVoice int8 model, 4 GB RAM recommended (3 GB is tight).

## Architecture

```
data/voice/
  VoiceModel.kt             model metadata (@Serializable)
  VoiceModelManager.kt      import from SAF tree, list, delete
  AsrEngine.kt              engine interface (load / recognize / release)
  SherpaOnnxEngine.kt       sherpa-onnx OfflineRecognizer implementation
ime/voice/
  AudioRecorder.kt          AudioRecord -> FloatArray, amplitude StateFlow
  VoiceInputManager.kt      orchestration + VoiceUiState StateFlow
  VoiceInputDialog.kt       floating dialog showing status and transcript
ui/voice/
  VoicePermissionActivity.kt  transparent activity requesting RECORD_AUDIO
```

Reused infrastructure:

- `TrimeInputMethodService.showDialog()`
  (`TYPE_APPLICATION_ATTACHED_DIALOG`) for the floating dialog, which leaves
  the keyboard in place instead of replacing the input area.
- `TrimeInputMethodService.updateComposingText()` and `commitText()` to put the
  result in the editor and commit it.
- `AppPrefs` delegates for settings.
- `CommonKeyboardActionListener`'s `KEYCODE_VOICE_ASSIST` branch to route the
  space long press.

## Flow

```
long press space
  -> General.voiceInputMode
       OFF      : nothing
       LOCAL    : VoiceInputManager.open()  (this document)
       EXTERNAL : switch to the system voice input method (old behaviour)
  -> VoiceInputDialog shows, VoiceInputManager.start()
       no permission -> VoicePermissionActivity
       no model      -> message + "open settings"
       else          -> load recognizer (cached) -> AudioRecorder.start()
  -> "Stop" -> AudioRecorder.stop() -> SherpaOnnxEngine.recognize()
  -> updateComposingText(text): the text appears in the editor, underlined
  -> [Confirm] commitText / [Retry] record again / [Cancel] clear
```

The dialog calls `updateComposingText` rather than committing directly, so the
text sits in the editor for review. While a transcription is pending, Rime must
not overwrite the composing string; `VoiceInputManager` owns that state and
clears it before any new Rime interaction. This is the most delicate part of the
integration and the first thing to regression-test.

## Settings

*General → Long press space* (also shown on the *Local voice input* page): `Disabled` / `Local voice input` / `System voice
input method` (default, preserves the old behaviour).

*Settings → Local voice input*:
- Engine (currently only sherpa-onnx)
- Model (the imported models)
- Language (`auto`, `zh`, `en`, `ja`, `ko`, `yue`)
- Import model / Delete model

## Permissions

`RECORD_AUDIO` was added to the manifest. Input method services cannot show
runtime permission dialogs, so `VoicePermissionActivity` (a small, no-history
activity) requests it on the IME's behalf. No new permission is needed otherwise;
models live in app-private storage and are imported through SAF.

## Testing checklist

1. `script/fetch-sherpa-onnx.sh`, then build.
2. Import `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09`.
3. Set *Long press space* to *Local voice input*.
4. Long press space, speak, stop, confirm. Check the text lands in the editor.
5. Cancel and retry paths, and switching app while recording.
6. Confirm Rime composing still works right after a voice commit.
7. Measure first-token latency and peak memory on a low-end device.

## Future work

- Streaming recognizers for live partial text.
- Silero VAD to auto-stop on silence.
- Build sherpa-onnx/onnxruntime from source for F-Droid.
- Hotword / replacement dictionaries.
- Optional punctuation restoration models.
