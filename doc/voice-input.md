# Local (on-device) voice input

Design and implementation notes for Trime's optional, fully offline voice input.

## Goals

- Recognise speech **entirely on device**; audio never leaves the phone.
- The app keeps its current privacy property of **not requesting `INTERNET`**.
- Plug into the existing input flow: long press space, record, and insert the
  transcript straight into the editor, undoable for a moment.

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
| Result | Inserted straight into the editor; undoable for a few seconds |
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
| SenseVoice | `model.int8.onnx` + `tokens.txt` | **Recommended**: good Chinese, fast, multilingual; punctuates when ITN is on |
| Paraformer | `model.int8.onnx` + `tokens.txt` | Chinese |
| Whisper | `*-encoder.onnx` + `*-decoder.onnx` + `tokens.txt` | Multilingual, larger/slower, punctuates natively |
| Transducer | `encoder*.onnx` + `decoder*.onnx` + `joiner*.onnx` + `tokens.txt` | `zipformer` etc.; X-ASR punctuates and cases natively |
| Zipformer CTC | `model*.onnx` + `tokens.txt` | |

To add one, unzip a release and pick its folder from *Settings → Local voice
input → Import model*. The folder **name** is part of the detection, so keep the
release name.

### Recommended models

| | Download | Size | Languages | Punctuation |
| --- | --- | --- | --- | --- |
| **SenseVoiceSmall** (default) | [`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2) | ~230 MB | zh, en, ja, ko, yue | yes |
| **X-ASR** (Chinese + English mixed) | [`sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03.tar.bz2) | ~168 MB | zh, en | yes, plus casing |

### Punctuation

SenseVoice only emits punctuation (and Arabic digits) when inverse text
normalization is on. The engine always sets
`useInverseTextNormalization = true`, so an official SenseVoiceSmall model
punctuates out of the box: `开饭时间早上9点至下午5点。`.

The sherpa-onnx release names are easy to misread: several of the newer ones are
**dialect finetunes**, not newer versions of the same model.

- `…sense-voice-…-int8-2025-09-09` is the WSYue **Cantonese** finetune. It is a
  little better on Cantonese, but it emits **no punctuation at all**.
- `…paraformer-zh-int8-2025-10-07` is a **Sichuanese** finetune.

Whisper, X-ASR and the other models marked above punctuate natively. FireRedASR2
and the large CTC/transducer models do not, and would need the separate
CT-Transformer punctuation model as a post-processing step (not implemented).

Only **offline** models work. Folders whose name contains `streaming` (for
example `sherpa-onnx-x-asr-480ms-streaming-…`) are rejected at import time,
because the app only drives sherpa-onnx's offline recognizer.

### Baseline

- `arm64-v8a`, Android 8+ for the voice feature, ~230 MB free storage for a
  SenseVoice int8 model, 4 GB RAM recommended (3 GB is tight).

## Architecture

```
data/voice/
  VoiceModel.kt             model metadata (@Serializable)
  VoiceModelManager.kt      import from SAF tree, list, delete
  VoiceModelDetector.kt     model type detection from folder name and files
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
  -> a non-modal banner shows at the top, VoiceInputManager.start()
       no permission -> VoicePermissionActivity
       no model      -> message + "open settings"
       else          -> load recognizer (cached) -> AudioRecorder.start()
  -> "Stop" -> AudioRecorder.stop() -> SherpaOnnxEngine.recognize()
  -> commitText(text): the text lands in the editor straight away
  -> the banner offers [Undo] / [Retry] for a few seconds, then disappears
```

The transcript is **committed**, never left as a composing string, so voice
input never shares Rime's composing text. `[Undo]` only deletes anything while
the transcript is still exactly in front of the cursor, which makes it safe
alongside typing and cursor movement.

The banner window is non-modal (`showDialog(..., floating = true)`): it does not
dim the app and lets touches outside it through, so the user can keep browsing
while dictating. This is the most delicate part of the integration and the first
thing to regression-test.

## Settings

*General → Long press space* (also shown on the *Local voice input* page): `Disabled` / `Local voice input` / `System voice
input method` (default, preserves the old behaviour).

While either voice option is selected, the space bar shows a microphone
symbol; the label the theme binds to its own long press (usually a language or
keyboard switch) would otherwise advertise an action that no longer runs.

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
2. Import `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` (or the
   X-ASR release) and check that the transcript is punctuated.
3. Set *Long press space* to *Local voice input*.
4. Long press space, speak, stop. Check the text lands in the editor and the
   banner offers *Undo* / *Retry*.
5. Check *Undo* removes the text, *Retry* replaces it, and that the banner lets
   you keep scrolling the app behind it (it is not modal).
6. Confirm Rime composing still works right after a voice commit.
7. Measure first-token latency and peak memory on a low-end device.

## Future work

- Streaming recognizers for live partial text.
- Silero VAD to auto-stop on silence.
- Build sherpa-onnx/onnxruntime from source for F-Droid.
- Hotword / replacement dictionaries.
- Optional punctuation restoration models (CT-Transformer) for models that do
  not punctuate natively, such as FireRedASR2.
