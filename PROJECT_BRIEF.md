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

- **Android:** Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSessionService (moving to MediaLibraryService for Android Auto), Room, WorkManager, Coil 3.
- **Web:** React + TypeScript (Vite). Smart Speed and Voice Boost approximated with the Web Audio API.
- **Supabase:** Postgres with row-level security, auth, and edge functions as proxies for Podcast Index and the transcription API.
- **Episode IDs:** SHA-1 of `feedUrl + "\n" + guid`, first 10 bytes as hex. Stable across devices, which sync depends on.
- **Repo layout:** `android/`, `web/`, `supabase/` in one GitHub repo (`earshot`).

## 4. Phase plan and status

| # | Phase | Status |
|---|---|---|
| 1 | Setup: Android Studio, GitHub repo, (later) Supabase project and Podcast Index key | In progress: Android Studio installing on Serena's VM; GitHub repo not created yet |
| 2 | Core player: search/RSS/OPML, stream and download, background playback, speed, sleep timer, chapters | **Code written, not yet compiled.** First build happens on Serena's machine |
| 3 | Inbox triage + queue (drag reorder) + Android Auto (test with the Desktop Head Unit) | Next |
| 4 | Smart Speed + Voice Boost, per-podcast settings, "time saved" stat | |
| 5 | Supabase schema and auth, two-way sync, React web player, Podcast Index search | |
| 6 | Transcripts (feed first, cloud API fallback) + headphone-tap bookmarks + transcript search + Markdown export | |
| 7 | Automation rules + smart playlists | |
| 8 | GitHub Actions signed release builds | |

**Out of scope for v1:** iOS, social features, recommendations, video podcasts.

**Fixes for reviewers' complaints:** compact rows; unsubscribing keeps queued episodes (phase 3); drag-reorder subscriptions; downloads auto-retry and show a visible failed state; no ads; features never silently removed.

## 5. What phase 2 code contains (android/)

- `data/db`: Room entities (Podcast, Episode) and DAOs. Refreshing a feed updates metadata without touching play position or download state.
- `data/feed`: streaming RSS parser (RSS 2.0, iTunes and Podcasting 2.0 tags), OPML import, JSON chapters.
- `data/net/Http.kt`: HttpURLConnection wrapper that follows redirects manually (including http → https).
- `data/search/ItunesSearch.kt`: directory search.
- `data/Repository.kt`: subscribe, unsubscribe, refresh, import, download, chapters.
- `playback/PlaybackService.kt`: ExoPlayer + MediaSession. Saves position every 5 s, marks played at the end, skip back 15 s / forward 30 s.
- `playback/PlayerConnection.kt`: app-wide MediaController, now-playing state, speed, sleep timer.
- `work/`: RefreshWorker (every 2 h) and DownloadWorker (foreground, retries).
- `ui/`: ViewModel with a simple back stack (no navigation library), Library (Latest / Podcasts tabs), Podcast page, Add (search / URL / OPML), Player sheet, Mini player, Episode sheet.

## 6. Pinned versions (checked November 2026)

AGP 9.5.0 · Gradle 9.6.0 · Kotlin 2.4.20 (built into AGP 9; do NOT apply `org.jetbrains.kotlin.android`) · KSP 2.3.13 ·
Compose BOM 2026.11.01 · material-icons-extended 1.7.8 · activity-compose 1.13.0 · lifecycle 2.11.1 · core-ktx 1.19.1 ·
Media3 1.11.1 · Room 2.8.5 · WorkManager 2.12.0 · Coil 3.6.3 · compileSdk / targetSdk 36 · minSdk 26

## 7. Known risks to watch on first build

- The code has never been compiled against the Android libraries. Expect a few small fixes.
- AGP 9 built-in Kotlin with KSP and Room is new territory. If KSP errors appear, check the KSP version against the AGP/Kotlin pair first.
- `material-icons-extended` is no longer part of the Compose BOM, so it's pinned separately at 1.7.8.
