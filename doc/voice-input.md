# Local (on-device) voice input

Design and implementation notes for Trime's optional, fully offline voice input.

## Goals

- Recognise speech **entirely on device**; audio never leaves the phone.
- The app keeps its current privacy property of **not requesting `INTERNET`**.
- Plug into the existing input flow: long press space, record, and insert the
  transcript from the input bar, at the cursor the user chooses.

## Non-goals (v1)

- No cloud fallback, no account, no telemetry.
- No true streaming / live word-by-word output: the offline recognizer still
  transcribes whole utterances, but the capture is cut at natural pauses and
  each finished utterance is inserted while the user keeps speaking (see
  *Process perception*).
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
| Result | Finished utterances are cut at pauses and inserted at the cursor while the user is still speaking; the text is ordinary editor text, with no held result or undo |
| Streaming | Not used; utterances are segmented by pause instead |
| VAD | Silero VAD, bundled in `assets/silero_vad.onnx`; the fixed-threshold energy segmenter is only a fallback |

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
  AudioRecorder.kt          AudioRecord -> FloatArray, with a per-block callback
  VoiceSegmenter.kt         cuts the capture into utterances at pauses
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
  -> every block of audio is fed to the segmenter; when a pause closes an
     utterance, it is transcribed and inserted at the cursor right away
  -> tap the chip -> AudioRecorder.stop() -> the buffered utterance is
     transcribed too -> the bar hides
  -> the session also ends after 30 s without speech, or as soon as the user
     touches the keyboard (typing, layout switch, a panel)
```

## Process perception

A batch recognizer produces nothing until the recording stops, which makes the
user talk into the dark. To avoid that, the capture is segmented and each
finished utterance is inserted **while the user is still speaking**: the first
sentence is already in the editor before the second one is finished. This is the
"commit on pause" behaviour other input methods offer, and it is what makes the
result feel live without a streaming model.

The bar no longer has to hold a long transcript, so the single-line bar stays
readable: finished text lives in the editor, where the app scrolls it, and the
bar only shows the microphone and the level while listening.

An utterance ends after roughly 0.5 s of silence (or at 15 s of continuous
speech, the VAD's max segment length), and a short blip is discarded rather
than transcribed. The session keeps listening and keeps inserting utterances
until the user taps the microphone, until **30 s pass with no speech**, or as
soon as the user touches the keyboard (typing, switching layout, opening a
panel) — at that point they have moved on to manual input. There is no
wall-clock cap on how long a dictation may run: the recorder does not retain the
session, so memory is bounded by one segment, not by the session.

Silero is used instead of a fixed energy threshold on purpose. A real capture
showed one continuous sentence whose level fell from 0.03 to 0.004; an absolute
threshold either cut it after the loud opening or ignored the quiet rest. A
model-based VAD does not depend on the device's microphone level.

The inserted text is ordinary editor text: there is no held result, no undo and
no close button. The bar is only the recording indicator (microphone + level),
and the user corrects the committed text with the normal keyboard.

Touching the keyboard ends the session, so the voice chip and the candidate row
never compete: the first key press hands the bar back to Rime. The session is
also dropped when the input view goes away.

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
5. Speak a sentence or two with a pause in between: each finished sentence
   appears in the editor as you keep speaking, without tapping anything.
6. Stop (tap the microphone): the last utterance is transcribed and inserted,
   then the bar hides. The inserted text is editable like anything else.
7. Start a session and type on the keyboard: the session ends immediately,
   whatever the silence timer was doing.
8. Start a session, say nothing for 30 s: the microphone is released on its own
   and the bar hides.
9. Check that a very short noise does not produce a stray insert, and that
   leading/trailing silence is not transcribed.
10. Confirm Rime composing still works right after a voice insert, and that the
    space bar keeps typing a space the whole time (it is only highlighted).
11. Measure utterance latency (pause -> text in editor) and peak memory on a
    low-end device, and tune the segmenter thresholds if needed.

## Future work

- Silero VAD is the production segmenter; the remaining gap is sub-pause,
  word-by-word feedback, which needs either a true streaming engine
  (sherpa-onnx `OnlineRecognizer`; weaker Chinese, no native punctuation) or
  re-decoding the growing buffer every few hundred ms (near-live but O(n²) and
  jittery).
- Tune the VAD's pause length and the max utterance length, and expose them as
  settings. An optional long silence timeout that ends the session could sit
  next to them.
- Build sherpa-onnx/onnxruntime from source for F-Droid.
- Hotword / replacement dictionaries.
- Optional punctuation restoration models (CT-Transformer) for models that do
  not punctuate natively, such as FireRedASR2.
