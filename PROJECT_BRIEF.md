# Earshot: Project Brief

A personal podcast app for Serena, used only by her. Native Android app, a web player and Android Auto, with sync through Supabase.
"Earshot" is a working name.

---

## 1. Decisions (all confirmed by Serena)

| Topic | Decision |
|---|---|
| Audience | Just Serena: no app store, no public accounts |
| Platforms | Android phone, web/desktop browser, Android Auto |
| Core features (all in v1) | Smart Speed + Voice Boost · Inbox triage → queue · Bookmarks/snips + transcripts · Power automation rules |
| Transcripts | Use the feed's Podcasting 2.0 transcript first; otherwise a cloud transcription API on demand, cached per episode. Provider not chosen yet: compare current pricing and accuracy in phase 6 |
| Sync | Hosted BaaS: Supabase (auth, Postgres, edge functions; API keys live server-side) |
| Stack | Native Kotlin Android app (Compose, Media3, Room, WorkManager) + separate React/TypeScript web app (Vite) |
| UI | Compact and dense, dark by default (Castro-like rows, many episodes per screen) |
| Dev setup | Android Studio for daily development, plus GitHub Actions later for signed release APKs |
| Directory | Phase 1 uses Apple's keyless iTunes Search API. Switch to Podcast Index through a Supabase edge function in phase 5 |
| Inbox (phase 3) | Every newly released episode found by a refresh goes to the Inbox. Subscribing to one podcast adds only its newest episode; OPML imports add nothing |

## 2. Research summary (what the best apps get right and wrong)

- **Overcast:** Smart Speed (trims silences without distortion) and Voice Boost (loudness normalization plus compression) are its most-loved features.
- **Pocket Casts:** cross-platform sync, filters, folders and a web player. Backlash over the v7 redesign that removed features, and over Plus going from $10 to $40 a year.
- **Podcast Addict:** about 4.6★ from 590K reviews. Deep automation with per-podcast rules, cheap, but a learning curve.
- **Castro:** inbox → queue triage, which heavy listeners call game-changing.
- **Snipd:** tap headphones to save a "snip" with transcript and AI summary, transcript search, export to Notion or Obsidian.
- **AntennaPod:** open source, no tracking, gPodder/Nextcloud sync.
- **Common complaints:** features hidden or removed in redesigns, subscription price hikes, ads and upsells, bloated rows (about 3 episodes per screen), download errors.
- **Wishlist items to build in:** quick bookmark or note while driving, smarter smart playlists, keep queued episodes after unsubscribing, drag-to-reorder subscriptions, Smart Speed music detection.
- **Open standard:** Podcasting 2.0 namespace (`podcast:transcript`, `podcast:chapters`, `podcast:person`, `podcast:funding`) and the Podcast Index API.

## 3. Architecture

- **Android:** Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaLibraryService (since phase 3, for Android Auto), Room, WorkManager, Coil 3.
- **Web:** React + TypeScript (Vite). Smart Speed and Voice Boost approximated with the Web Audio API.
- **Supabase:** Postgres with row-level security, auth, and edge functions as proxies for Podcast Index and the transcription API.
- **Episode IDs:** SHA-1 of `feedUrl + "\n" + guid`, first 10 bytes as hex. Stable across devices, which sync depends on.
- **Repo layout:** `android/`, `web/`, `supabase/` in one GitHub repo (`earshot`).

## 4. Phase plan and status

| # | Phase | Status |
|---|---|---|
| 1 | Setup: Android Studio, GitHub repo, (later) Supabase project and Podcast Index key | Android Studio + GitHub repo done (VM signed in to GitHub, phone paired for Wi-Fi debugging). Supabase and Podcast Index key later |
| 2 | Core player: search/RSS/OPML, stream and download, background playback, speed, sleep timer, chapters, download auto-delete + Settings screen | **Done 2026-11-26.** Builds, tested on Serena's phone, merged to main (bd30ae7). Airplane-mode download tests skipped by choice |
| 3 | Inbox triage + queue (drag reorder) + Android Auto (test with the Desktop Head Unit) | **Done 2026-11-26.** Tested on Serena's phone and on the Desktop Head Unit driving the phone; pushed to main |
| 4 | Smart Speed + Voice Boost, per-podcast settings, "time saved" stat, adjustable skip lengths (long-press a skip button: 5 s, 10 s, 15 s, 30 s, 1 min, 5 min, 15 min) | **Built 2026-11-26** on branch `claude/project-thread-vtxk1w`, installed on Serena's phone; library upgrade checked; player, dialogs, Settings and podcast settings checked by tapping (no audio). Waiting on Serena's listening test and the Desktop Head Unit test, then merge to main |
| 5 | Supabase schema and auth, two-way sync, React web player, Podcast Index search | |
| 6 | Transcripts (feed first, cloud API fallback) + headphone-tap bookmarks + transcript search + Markdown export | |
| 7 | Automation rules + smart playlists | |
| 8 | GitHub Actions signed release builds | |

**Out of scope for v1:** iOS, social features, recommendations, video podcasts.

**Fixes for reviewers' complaints:** compact rows; unsubscribing keeps queued episodes (done in phase 3); drag-reorder subscriptions; downloads auto-retry and show a visible failed state; no ads; features never silently removed.

## 5. What the code contains (android/, through phase 4)

- `data/db`: Room entities (Podcast, Episode) and DAOs. Refreshing a feed updates metadata without touching play position, download, inbox or queue state.
- `data/feed`: streaming RSS parser (RSS 2.0, iTunes and Podcasting 2.0 tags; current and legacy Podcasting 2.0 namespaces), OPML import, JSON chapters.
- `data/net/Http.kt`: HttpURLConnection wrapper that follows redirects manually (including http → https).
- `data/search/ItunesSearch.kt`: directory search.
- `data/Repository.kt`: subscribe, unsubscribe, refresh, import, download, chapters, inbox and queue operations, download cleanup.
- `data/OpmlImporter.kt`: app-wide OPML import, 4 feeds at a time with retries, progress banner, names failed feeds, detects Intune-encrypted files.
- `playback/PlaybackService.kt`: Media3 **MediaLibraryService** with ExoPlayer (custom audio chain, see below). Saves position every 5 s. Skip back / forward buttons in the notification and Android Auto (media button preferences in the back/forward slots), using the current skip lengths. Android Auto browse tree with 4 tabs: Queue, Inbox, Latest (100 newest), Podcasts → episodes (100). Playback resumption (Bluetooth or car "resume") plays the top of the queue. Tells Android Auto when the queue, inbox or podcast list changes (`notifyChildrenChanged`). `onGetItem` must resolve folder ids too, or Media3 rejects Auto's subscriptions and the car's lists go stale.
- `playback/EpisodeMedia.kt`: builds MediaItems and browse folders; the resume-position rule.
- `playback/PlayerConnection.kt`: app-wide MediaController, now-playing state, speed, sleep timer. Plays by sending only the episode id; the service looks it up (same path as Android Auto). At app start it loads the top of the queue, paused, into the mini player.
- `work/`: RefreshWorker (every 2 h), DownloadWorker (foreground, retries, records downloadedAt), CleanupWorker (daily download cleanup).
- **Inbox:** see the decision in section 1. Playing or marking played takes an episode out of the Inbox. Swipe right adds it to the end of the queue, swipe left archives it, plus "Archive all".
- **Queue:** ordered by `queuePosition` (renumbered 0..n-1 on every change). The player holds one episode at a time; the queue lives in the database. Playing anything (from the app or the car) moves it to the top. When it ends it's marked played (which removes it from the queue and inbox) and the next one starts, unless "sleep at end of episode" is set. "Play next" puts it second, "Play last" at the end. Drag handle to reorder (Reorderable library), swipe left to remove. Queued episodes auto-download (Settings toggle, default on).
- **Unsubscribe:** if any episode is queued or loaded in the player, the podcast is kept hidden (`subscribed = 0`, no refreshes) with only those episodes; everything else and its downloads are deleted. When its last queued episode goes, the podcast and its downloads are removed. Subscribing again brings it back.
- **Download auto-delete (Serena's rules):** finished/marked-played episodes lose their download right away (deferred while still loaded in the player). Unfinished downloads expire N days (default 7; Never/3/7/14/30) after the later of download time and last played. Never deletes the loaded, queued or downloading episode. The cleanup also deletes stray files in the downloads folder that no episode points to (only files older than 10 minutes, never `.part` files).
- **Settings screen** (gear in Library top bar): "Download queued episodes", "Delete downloads when finished", "Delete unfinished downloads after". Stored in `data/Settings.kt`.
- **Room schema is version 4** with real migrations (1→2: downloadedAt; 2→3: episodes.inInbox, episodes.queuePosition, podcasts.subscribed; 3→4: nullable podcasts.speed, smartSpeed, voiceBoost, skipBackMs, skipForwardMs and episodes.timeSavedMs). Every future schema change needs a real Migration: the destructive fallback would wipe Serena's library. Back up the phone database before installing a schema change (`adb exec-out run-as com.earshot.app cat databases/earshot.db`, plus `-wal` and `-shm`); `sqlite3.exe` is in platform-tools for checking it.
- **Feed refresh** writes only the feed's fields (`PodcastDao.updateMeta`), so it can't overwrite settings or subscription state changed meanwhile.
- **Playback settings (phase 4):** app-wide defaults in `data/Settings.kt` (`PlaybackDefaults`, a StateFlow; defaults 1.0×, Smart Speed off, Voice Boost off, back 15 s, forward 30 s). A podcast overrides any of them (null = use the default). `PlaybackPrefs.resolve` combines them. The service observes the loaded episode's podcast plus the defaults and applies changes immediately.
  - **Player:** speed chip opens a Playback dialog (speed, Smart Speed, Voice Boost, "Just for <podcast>"). Smart Speed and Voice Boost chips toggle directly. Rule: a change goes to the podcast if it already has its own audio settings, otherwise to the defaults. Ticking "Just for <podcast>" copies the current values to the podcast; unticking clears them. Skip lengths work the same way, as a pair.
  - **Skip lengths:** tap to skip, long-press either button for the picker. `playback/SkipLengths.kt`: the session's player is a `ForwardingPlayer` (`SkipLengthPlayer`) whose seekBack/seekForward use the current lengths, so the notification, lock screen, headset and Android Auto follow them. The media button preferences are rebuilt when lengths change: numbered icons for 5/10/15/30 s, plain skip icon for 1/5/15 min, with the length in the name ("Forward 1 minute"). The app skips by exact length (`PlayerConnection.skipBy`).
  - **Podcast page:** sliders icon (also in ⋮ menu) opens Playback settings: each item is "Default (x)" or its own value.
  - **Settings:** Playback section (defaults) and Time saved section.
- **Audio chain (phase 4):** `playback/SmartAudioChain.kt` replaces Media3's default chain: silence shortening → Voice Boost → Sonic speed (Voice Boost after silence detection, so boosted room noise still counts as silence). Smart Speed = Media3 `SilenceSkippingAudioProcessor` tuned: pauses over 0.15 s are cut to 25%, at most 0.5 s kept. Voice Boost = `playback/VoiceBoostProcessor.kt`: slow AGC toward −20 dBFS (−6 to +12 dB, gated below about −44 dBFS), 3:1 compressor above −12 dBFS, soft limiter. Always in the chain, passes through untouched when off, so toggling needs no player reset. Tuning values are constants in those two files.
- **Time saved (phase 4):** the service ticks every second: speed saves t×(s−1); Smart Speed's skipped frames (episode time) count as skipped÷s. Totals in prefs (`saved_speed_ms`, `saved_smart_ms`), per episode in `episodes.timeSavedMs`, flushed every 5 s. Shown in Settings and under the player buttons ("Saved 3m 12s on this episode"). Speeds below 1× subtract.
- `ui/`: ViewModel with a simple back stack (no navigation library). Library tabs: **Queue · Inbox · Latest · Podcasts**, with counts. Podcast page, Add (search / URL / OPML), Player sheet (with Chapters chip), Mini player, Episode sheet (Play, Download, Played, Play next, Play last or Remove from queue, Archive), Settings, import banner.

## 6. Pinned versions (checked November 2026)

AGP 9.5.0 · Gradle 9.6.0 · Kotlin 2.4.20 (built into AGP 9; do NOT apply `org.jetbrains.kotlin.android`) · KSP 2.3.13 ·
Compose BOM 2026.11.01 (Material3 1.5.0) · material-icons-extended 1.7.8 · activity-compose 1.13.0 · lifecycle 2.11.1 · core-ktx 1.19.1 ·
Media3 1.11.1 (latest stable; 1.12.0 is at RC and stabilizes the media button preferences API) · Room 2.8.5 · WorkManager 2.12.0 · Coil 3.6.3 ·
Reorderable (`sh.calvin.reorderable:reorderable`) 3.1.0 (Compose Foundation still has no built-in reorderable list) ·
compileSdk 37.1 (`compileSdk { version = release(37) { minorApiLevel = 1 } }`; required by Compose 1.13 and friends) · targetSdk 36 · minSdk 26

## 7. Build and test setup

- Serena's Windows VM builds the app: repo at C:\repos\earshot, `gradlew.bat assembleDebug` (2 to 6 min). JAVA_HOME isn't set; use C:\Program Files\Android\Android Studio\jbr. No SDK command-line tools; AGP downloads SDK platforms itself. When Gradle runs from a script, give it no stdin (`< /dev/null`), or it can hang.
- Claude may run builds, installs and fixes on the VM through Remote Control. Her phone (Galaxy Z Fold6, Android 17) is paired over Wi-Fi debugging; the adb connect port changes when wireless debugging restarts. Ask before taking over the phone, and check what's on screen before each batch of taps, because she may pick it up mid-test. Phone audio may go to her Edifier speaker, so pause test playback promptly.
- **Android Auto testing:** Desktop Head Unit 2.0 is installed at %LOCALAPPDATA%\Android\Sdk\extras\google\auto. Android Auto developer mode is on and its first-run consent is accepted. Steps: phone Settings > Android Auto > ⋮ > Start head unit server; `adb forward tcp:5277 tcp:5277`; run `desktop-head-unit.exe`. Its console accepts `tap x y` and `screenshot <file>`. Android Auto's "Start music automatically" is on, so connecting can start Spotify.
- The cloud environment can't reach Google's SDK servers, so Android builds can't run there.

## 8. Open issues and decisions

- **Phase 4 still to test (Serena):** how Smart Speed and Voice Boost sound on real episodes (tuning constants may need adjusting after listening), time saved counting up while playing, and the skip buttons in the Desktop Head Unit (icons and lengths, including a 1-minute button with the plain icon).
- Voice Boost and Smart Speed only apply to normal PCM playback; they do nothing if Android ever uses offload or float output (both off today).
- Smart Speed doesn't detect music (wishlist item): shows with background music under speech get little or no trimming.
- Changing speed or toggling Smart Speed from the player while a podcast has its own settings changes that podcast's settings (by design; see section 5). Watch whether that feels right in use.
- Mini player has no skip buttons (the long-press picker is in the full player only).
- Android Auto's now-playing **Queue** button shows only the current episode, and the car's skip next/previous aren't offered (the skip back / forward buttons take those slots). The full queue is in the Queue tab. Could be fixed by mirroring the queue into the player's playlist.
- Android Auto shows a **search** button, but search isn't implemented yet (`onSearch` / `onGetSearchResult`). Natural fit for phase 5 (Podcast Index) or sooner.
- In the car, the Latest tab and a podcast's episode list don't refresh live (Queue, Inbox and Podcasts do); reopening them reloads.
- A feed that changes all its episode guids would flood the Inbox (every episode looks new). Rare; could cap new-episode inboxing per refresh.
- Removing an episode from the queue keeps its download; the normal expiry rule (default 7 days) removes it later.
- `Components.kt` / `PlayerSheet.kt`: `rememberModalBottomSheetState` is deprecated (still works). Swap it out and test the sheets on the phone.
- `android/gradle/gradle-daemon-jvm.properties` (Gradle-generated, asks for Java 25) is untracked. Gradle recommends committing it: decide.
- Lint hasn't been run yet.
- An OPML import is lost if Android kills the app mid-import (re-running is safe; existing feeds are skipped).
- Embedded ID3 chapters aren't read yet (only Podcasting 2.0 JSON chapters).
- Not yet tested: offline playback of downloads and the failed-download retry (airplane-mode steps skipped), and Android Auto in a real car.
