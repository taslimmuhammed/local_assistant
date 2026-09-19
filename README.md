# Local Assistant

A chat app that runs entirely on-device. Gemma 4 E4B via LiteRT-LM, and a context
window that never runs out.

Nothing about a conversation is written to disk. The transcript and its running
summary live in memory for as long as the app does, and are gone when it closes:
no database, no history, no search, no memory across chats.

Network access is used for exactly one thing: downloading the model. Inference,
conversations and memory never leave the phone.

## Running it

```bash
./gradlew installDebug
```

First launch downloads `gemma-4-E4B-it-gpu.litertlm` (2.97 GB) from Hugging Face,
then measures the usable context window once. To skip the download, push the file
yourself:

```bash
adb push gemma-4-E4B-it-gpu.litertlm /sdcard/Android/data/com.local.assistant/files/models/
```

## Layout

| Package | What lives there |
|---|---|
| `llm/` | LiteRT-LM engine wrapper, backend selection, memory tools |
| `context/` | Calibration probe, budgets, compaction, the conversation orchestrator |
| `data/` | In-memory message model, app settings |
| `download/` | Resumable model download as foreground work |
| `ui/` | Compose screens |

## The thing this app is actually about

### It does not run out of context

`EngineConfig.maxNumTokens` is a request, not a contract. LiteRT-LM accepts a value
larger than the model bundle's KV tensors can serve and only fails much later,
mid-conversation, with `Chosen prefill work group size exceeds available state
entries`. There is no API to ask for the real ceiling
([issue #3444](https://github.com/google-ai-edge/LiteRT-LM/issues/3444)).

So `CalibrationProbe` walks up to the wall once, on first run for a given model,
and remembers where it was. Every budget in `ContextBudget` is a fraction of that
measurement rather than a constant. Past 55% occupancy, `ChatController` folds the
oldest turns into a running summary and rebuilds the conversation from that summary
plus the recency window — rebuilding is what actually frees the KV cache, since
there is no way to evict the middle of a conversation.

Behind that sits a catch-and-retry net: if the runtime hits its internal limit
anyway, the turn is compacted and replayed once. It should almost never fire, and
`ContextSoakTest` asserts that it fires at most once across 300 turns. If it starts
carrying the design, the watermark is set too high.

Token accounting uses the runtime's own `Conversation.getTokenCount()`, so there is
no character-count estimation anywhere in the loop.

On the KV cache specifically: `maxNumTokens` is the only knob LiteRT-LM 0.17.1
exposes — there is no cache quantisation setting, no sliding-window config and no
session cloning in the Kotlin API. So cache *management* here means the rebuild
above, which is the only way to reclaim it, plus keeping the summariser in its own
short-lived conversation so it never occupies the chat's cache. The prompt ordering
is a separate concern: it preserves prefix reuse, which saves re-prefill time
rather than cache bytes.

## Decisions that differ from the obvious choice

**No Hilt, no KSP.** LiteRT-LM 0.17.1 ships classes with Kotlin metadata version
2.4.0, so the project must compile on Kotlin 2.4. No KSP release targets 2.4 yet
(latest is built against 2.3.20), and Hilt needs it. A hand-wired `AppContainer`
costs less than working around that.

**Requested window of 16k, capped at 32k.** The Gemma 4 E4B bundle is documented at
32k. Memory is not what limits this: the model uses grouped-query attention with 2
KV heads at a 256 head dim, 18 of its 42 layers share KV, and five in six of the
rest are sliding-window at 512 — so only 4 layers hold a cache that grows with
context. That works out near 1 KiB per token at int8, putting a full 32k window in
the low hundreds of MB. What does bind is decode speed, which degrades as those
full-attention layers fill, so the default sits at 16k and Settings exposes 4k
through 32k. Note the published 710 MB figure was measured at a 2048 context and so
carries almost no KV cache.

**GPU backend, not CPU.** On a current flagship, E4B on GPU runs ~1,293 prefill and
~22 decode tk/s at 710 MB peak RAM, against ~195/~18 at 3,283 MB on CPU. With
multi-token prediction enabled decode roughly doubles again. CPU remains as an
automatic fallback if GPU init fails, and Settings shows which one is live.

**Compose BOM pinned to 2026.06.01.** Newer BOMs pull Compose 1.12, which requires
AGP 9.x and `compileSdk 37`.

## Voice

Phase 2. `TranscriptionSource` defines the seam; `UnavailableTranscriptionSource`
is the placeholder and the mic button stays hidden while it reports unavailable.

The intended implementation is Moonshine v2 (`ai.moonshine:moonshine-voice`, ONNX
`.ort`, ~26 MB tiny English model, MIT for English). Speech goes to text, the text
lands in the input field, and you read and edit it before pressing send — never
auto-send. That is the reason for a separate STT path rather than Gemma 4's native
audio input: a model that hears the microphone directly gives you nothing to review.

## Tests

```bash
./gradlew testDebugUnitTest          # 8 tests, no device needed
./gradlew connectedDebugAndroidTest  # the soak test, needs a device and the model
```

The soak test is skipped unless the model file is present on the device:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.local.assistant.ContextSoakTest
```

## Not here on purpose

No chat storage, no history, no search, no memory across chats, no reminders. The
app is a chat client over a local model and forgets everything on exit.

**One exception, and it is deliberate:** app *settings* are still stored, through
DataStore. That is which model was downloaded, CPU or GPU, the requested window,
and the measured context ceiling. Dropping those would mean re-running the
calibration probe on every launch and losing track of the 3 GB file on disk. None
of it is conversation data. Delete `data/AppSettings.kt` and its call sites if you
want the app to hold nothing at all between launches.
