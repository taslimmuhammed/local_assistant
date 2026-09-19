# Local Assistant

A chat app that runs entirely on-device. Gemma 4 E4B via LiteRT-LM, chat history
you can return to, and a context window that never runs out.

There is deliberately no cross-chat memory: the assistant remembers the
conversation you are in, including its summarised earlier turns, and nothing more.

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
| `data/` | SQLite + FTS5 search, settings |
| `download/` | Resumable model download as foreground work |
| `ui/` | Compose screens |

## The two things this app is actually about

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

### Chats behave the way you expect

Opening the app starts a new chat. Earlier ones live in the drawer, grouped by age,
and reopening one restores its full transcript along with its running summary, so
you can carry on mid-thread days later.

A chat is named after the first thing you said in it, derived rather than
model-generated: a title is worth close to nothing and a generation would cost a
second of GPU on every new conversation. Rename or delete from a long-press.

Empty chats never reach the history. Launching the app and closing it again reuses
the unused chat rather than stacking blank rows, which is also why
`isSessionEmpty` decides whether `start()` creates a session or adopts one.

Every message is indexed into FTS5, so the drawer's search box finds a line across
all chats, BM25-ranked, and opens the chat it came from. Deleting a chat drops its
messages and its index rows together.

## Decisions that differ from the obvious choice

**No Room, no Hilt, no KSP.** LiteRT-LM 0.17.1 ships classes with Kotlin metadata
version 2.4.0, so the project must compile on Kotlin 2.4. No KSP release targets
2.4 yet (latest is built against 2.3.20), and both Room and Hilt need it. Plain
SQLite and a hand-wired `AppContainer` cost less than working around that.

**Bundled SQLite, not the platform's.** Android's SQLite is compiled *without*
FTS5 — `CREATE VIRTUAL TABLE ... USING fts5` fails with "no such module: fts5" even
on API 36. Verified on an emulator, see `Fts5ProbeTest`. So the app ships
`androidx.sqlite:sqlite-bundled` (SQLite 3.50.1), which also means identical SQLite
behaviour on every device instead of whatever the OEM built.

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
./gradlew testDebugUnitTest          # 23 tests, no device needed
./gradlew connectedDebugAndroidTest  # 25 tests, needs a device or emulator
```

The soak test is skipped unless the model file is present on the device:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.local.assistant.ContextSoakTest
```

## Not here on purpose

No cross-chat memory, no learned user profile, no reminders or calendar. The app
is a chat client over a local model. If persistent memory comes back, the pieces
it would build on — FTS5 over every message, per-chat summarisation, the tool
plumbing in `LlmEngine.createConversation` — are already in place.
