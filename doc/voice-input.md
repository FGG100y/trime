# Local (on-device) voice input

Design and implementation notes for Trime's optional, fully offline voice input.

## Goals

- Recognise speech **entirely on device**; audio never leaves the phone.
- The app keeps its current privacy property of **not requesting `INTERNET`**.
- Plug into the existing input flow: long press space, record, and insert the
  transcript from the input bar, at the cursor the user chooses.

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
| Result | Held in the input bar; inserted at the cursor on a tap, undoable |
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
  AudioRecorder.kt          AudioRecord -> FloatArray
  VoiceInputManager.kt      orchestration + VoiceUiState StateFlow
ime/bar/ui/
  VoiceSuggestionUi.kt      status/transcript chip in the always-bar
ui/voice/
  VoicePermissionActivity.kt  transparent activity requesting RECORD_AUDIO
```

Reused infrastructure:

- The always-bar (`AlwaysUi` / `InputBarDelegate`) hosts the voice chip as a new
  `AlwaysUi.State.Voice`, taking over the toolbar area while a session is
  active without touching the keyboard.
- `TrimeInputMethodService.commitText()` to insert the result, and
  `CurrentInputConnection.finishComposingText()` first so it is not merged into
  an active Rime composition.
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
  -> open()
       unsupported ABI -> toast
       no permission   -> open VoicePermissionActivity + toast
       no model        -> open the voice settings + toast
       else            -> load recognizer (cached) -> AudioRecorder.start()
  -> while recording the bar shows a highlighted microphone + "Listening…",
     the space bar's microphone is highlighted, and tapping the chip stops
  -> tap the chip -> AudioRecorder.stop() -> SherpaOnnxEngine.recognize()
  -> the transcript is shown in the bar, but is **not inserted**
  -> tap the chip    -> commitText(text) at the cursor, the chip offers Undo
     tap the chip's ✕ -> drop the transcript and close the session
```

The transcript is **never inserted automatically**, so it always lands where the
user wants and there is no fragile "undo an automatic insert" path. Inserting is
a deliberate tap; `Undo` only backs that out while the text can still be located
(right before the cursor, or at the absolute range recorded at insert time).
Anything else reports `voice_undo_failed` and leaves the transcript in the bar.

While the user types, Rime candidates take over the bar and the voice chip is
hidden; it comes back as soon as the candidate row clears, so "type to add
something, then insert the transcript" works. The chip is dropped on the next
recording, on ✕, or when the input view goes away.

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
4. Long press space: the bar shows a highlighted microphone and "Listening…",
   and the space bar's microphone is highlighted. Tap the chip to stop.
5. After recognising, the transcript appears in the bar but is **not** in the
   editor yet. Tap it: it is inserted at the cursor and the chip now shows
   *Undo*.
6. Tap *Undo*: the text is removed and the chip is back to insertable. Move the
   cursor, type into the editor until candidates show (the chip hides), then
   clear the composition (the chip returns) and insert it at the new cursor.
7. Check the fallback: undo after typing around the transcript shows the
   "couldn't undo" toast instead of deleting the wrong text.
8. Confirm Rime composing still works right after a voice insert, and that the
   space bar keeps typing a space the whole time (it is only highlighted).
9. Measure first-token latency and peak memory on a low-end device.

## Future work

- Streaming recognizers for live partial text.
- Silero VAD to auto-stop on silence.
- Build sherpa-onnx/onnxruntime from source for F-Droid.
- Hotword / replacement dictionaries.
- Optional punctuation restoration models (CT-Transformer) for models that do
  not punctuate natively, such as FireRedASR2.
