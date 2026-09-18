# HN Hands-Free – Implementation Plan

A personal Android app that walks through the Hacker News front page by audio:
it speaks a short summary of each story, and on request reads the full article.
Controlled entirely via headphone / Bluetooth media buttons. Sideloaded, not
published to the Play Store.

Work through the phases in order. After each phase, stop, summarize what was
done, and tell me how to verify it on the device before continuing.

---

## Core decisions (don't revisit without asking)

- **Native Kotlin, not React Native/Expo.** The app is essentially a media
  player with a generated playlist. Media3 (`MediaSessionService` + ExoPlayer)
  gives us background playback, the media notification, lock-screen controls,
  audio focus and headphone-button handling for free. In RN this would need
  react-native-track-player plus a custom native TTS-to-file module anyway.
- **UI: Jetpack Compose**, single activity, minimal. The app is used with the
  screen off; the UI is only for settings and a "now playing" view.
- **TTS: Android system `TextToSpeech`, synthesized to files**
  (`synthesizeToFile`), then played through ExoPlayer. Speaking directly via
  `speak()` would bypass the media session and break button control.
  Keep TTS behind an interface so a cloud TTS can be swapped in later.
- **Summaries: Gemini API free tier** (Google AI Studio key, no billing
  account), default model `gemini-3.5-flash-lite`, model name configurable in
  settings. Called directly from the app (BYOK, no backend). Keep it behind a
  `Summarizer` interface so Claude (Haiku) or on-device Gemini Nano can be
  swapped in later. *Changed 2026-09-18 from Claude Haiku: Claude Pro doesn't
  include API usage, Gemini's free tier costs nothing. Trade-off: free-tier
  prompts may be used by Google to improve its products; fine for public HN
  content.*
- **Article extraction: OkHttp + Readability4J** (Kotlin port of Mozilla
  Readability).
- **Build from the terminal** (Gradle CLI, adb). No Android Studio dependency.
  Dev machine is Manjaro Linux.

## Control mapping (headphone buttons)

| Button          | During summary              | During full article        |
|-----------------|-----------------------------|----------------------------|
| Next            | Skip to next story          | Abort article, next story  |
| Previous        | **Read full article**       | Restart current paragraph  |
| Play/Pause      | Pause / resume              | Pause / resume             |

Implement via a Media3 `ForwardingPlayer` that intercepts `seekToNext` /
`seekToPrevious` (and their `...MediaItem` variants), so notification, lock
screen and Bluetooth all behave identically. Note: many headsets map
double-press to Next and triple-press to Previous – that's fine.

If no button is pressed, the queue advances to the next summary automatically.

---

## Phase 0 – Toolchain

1. Check for JDK 17 and the Android SDK; if missing, give me the Manjaro
   commands (e.g. `jdk17-openjdk`, AUR `android-sdk-cmdline-tools-latest`),
   don't run `sudo` yourself.
2. Install via `sdkmanager`: platform-tools, a current platform, build-tools.
3. Verify `adb devices` sees my phone (USB or wireless debugging).

**Verify:** `adb devices` lists the phone.

## Phase 1 – Project skeleton

- Gradle Kotlin DSL, version catalog, minSdk 26, current targetSdk.
- Package: `de.reibisch.hnaudio` (adjust if I say otherwise).
- Dependencies: Compose, Media3 (exoplayer, session), OkHttp,
  kotlinx.serialization, kotlinx.coroutines, Readability4J, DataStore.
- One screen with a "Hello" text; `./gradlew installDebug` works.

**Verify:** app launches on the phone.

## Phase 2 – Data layer

- `HnClient`: fetch `https://hacker-news.firebaseio.com/v0/topstories.json`,
  then items (`/v0/item/{id}.json`) with bounded concurrency.
- Model: id, title, url, score, commentCount, text (for Ask/Show HN),
  top-level comment ids.
- `ArticleExtractor`: fetch URL with a desktop-ish User-Agent, 10 s timeout,
  run Readability4J, return title + clean paragraph list.
  Classify failures: no URL (text post), PDF, video (YouTube etc.),
  GitHub repo (use README if cheap), paywall/empty extraction.
- Unit tests with a few saved HTML fixtures.

**Verify:** a debug screen or log dump showing the top 10 stories with
extracted text length / failure reason.

## Phase 3 – Summarizer

- `Summarizer` interface; `GeminiSummarizer` calling
  `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
  with the key in the `x-goog-api-key` header (not the URL, so it never ends up
  in logs).
- Input: title, domain, score, comment count, extracted article text
  (truncate to ~6k tokens). If extraction failed, summarize from the post
  text and the top ~5 comments instead, and say so in the summary.
- Prompt goals: 2–4 sentences, written for listening (no markdown, no lists,
  no URLs, spell out abbreviations where a TTS would stumble), say what the
  thing actually is and why people care. Plain text output only.
- Free-tier rate limits: on HTTP 429 honour the server's `retryDelay` (retry at
  most twice); if the daily quota is used up, fail with a clear message.
- API key entered in settings, stored in DataStore encrypted with an
  Android Keystore key. Never logged.
- Cache summaries on disk by story id, so refreshes don't burn quota.

**Verify:** summaries for the top stories shown on the debug screen and
printed to logcat.

## Phase 4 – TTS to files

- `SpeechRenderer` interface: `suspend fun render(text: String): List<File>`.
- System implementation: `TextToSpeech.synthesizeToFile`, chunked at
  paragraph/sentence boundaries under `getMaxSpeechInputLength()`.
  English voice, configurable speech rate.
- Files in `cacheDir/tts/`, deleted once played / on app start beyond a
  size limit.

**Verify:** a test button renders and plays one summary.

## Phase 5 – Playback service

- `PlaybackService : MediaSessionService` with ExoPlayer, foreground service
  type `mediaPlayback`, audio focus handling (pause for calls/notifications
  ducking), `POST_NOTIFICATIONS` permission request.
- Queue model: per story, one "intro + summary" item
  ("Story 3. <title>. 412 points, 230 comments. <summary>").
  Metadata (title, domain) set on each MediaItem so the notification shows it.
- `ForwardingPlayer` implementing the control mapping above.
  "Read full article" inserts the article chunks right after the current item
  and jumps to them; "Next" during an article removes remaining chunks.
- Short spoken cue before a full article ("Reading full article, about
  6 minutes.") and when the list is done.

**Verify (manual, screen off, Bluetooth headphones):**
play starts, Next skips, Previous reads the article, pause works,
a phone call pauses playback.

## Phase 6 – Prefetch pipeline

- Keep the next 2 stories fully prepared (extracted, summarized, rendered)
  ahead of the playhead; full article audio is rendered on demand, first
  chunk first so it starts quickly.
- Coroutine-based pipeline with cancellation when stories are skipped fast.
- Graceful degradation: if a step fails, announce "Couldn't load this one"
  and advance.

**Verify:** skipping quickly through 5 stories has no dead air > 2 s
on Wi-Fi.

## Phase 7 – Polish

- Remember heard story ids (DataStore or Room); skip them next session.
- Settings: API key, number of stories (default 30), speech rate,
  "include Ask/Show HN".
- Now-playing screen: current title, position, big buttons as fallback,
  "open in browser" to read later.
- "Save for later": long-press Play/Pause is usually taken by the voice
  assistant, so offer this only in the UI / notification as a custom action.

## Phase 8 – Install

- Generate a local release keystore (outside the repo, path in
  `local.properties`), `./gradlew assembleRelease`, `adb install -r`.
- `README.md` with build/install steps.

---

## Non-goals (for now)

- Play Store release, iOS, accounts, backend server.
- Voice commands / wake word (possible later; buttons are more reliable
  next to a vacuum cleaner).
- Reading comment threads in full.

## Cost check

Gemini free tier: 0 €. Limits are per minute and per day and change over
time; check them in Google AI Studio. Log token usage per session to logcat
so usage can be compared against the limits. (For reference, the original
Claude Haiku plan would have been roughly 20 cents per 30-story session.)
