# Earshot

A personal podcast player for Android, with a web player and Android Auto, kept in sync through Supabase.

- **Android app:** download the `.apk` from the [latest release](https://github.com/JPollack40/earshot/releases/latest)
  (it isn't on the Play Store, so Android asks you to allow the install).
- **Web player:** https://jpollack40.github.io/earshot/
- Sign in under **Settings > Sync** to sync your library. Accounts are invite-only (new sign-ups are switched off).

---

## Features

**Library**
- Add podcasts by searching (Podcast Index, with Apple as the fallback), pasting a feed URL, or importing an OPML file.
  Open a search result to look around and play episodes before subscribing.
- **Browse** page with trending shows, categories and "You might like" suggestions on each podcast page.
- Tabs for Podcasts (pin favourites to the top), New Episodes (swipe to queue or archive) and the Queue
  (drag to reorder, Continue listening at the top), plus smart playlists with saved filters and sorting.
- Per-podcast rules for new episodes (New Episodes, play next, end of queue or skip; title words; length) and tidy-up rules.
- Search inside a podcast's titles and show notes; mark older or newer episodes played in one go.
- Unfollowing a show keeps its queued and part-played episodes until you finish them.

**Player**
- Background playback with notification, lock-screen and headset controls; remembers your place in every episode.
- Speed 0.8×–3×, Smart Speed (shortens silences), Voice Boost (evens out loudness), and adjustable skip lengths
  (long-press a skip button), all with optional per-podcast settings. Time saved is shown in Settings.
- Sleep timer, chapters (Podcasting 2.0 and embedded), skipped chapters, undo for accidental jumps, and ad trim
  at the start and end of episodes.
- Downloads in the background with retries, automatic clean-up, Wi-Fi-only option, and a 10-minute streaming
  buffer with a stream cache.
- Rewind a little after a pause or interruption.

**Transcripts and AI**
- Transcripts from the feed when it has one, otherwise on demand (Deepgram); the transcript follows playback.
- Search across all saved transcripts; export a transcript as Markdown.
- Bookmarks from the app, the notification or the car, saved with the last minute of transcript and titled automatically.
- AI (Gemini, from the transcript): a summary with key moments, generated chapters, "Ask about this episode",
  and ad detection with automatic skipping or a Skip ad button.

**Sync, web and car**
- Subscriptions, pins, the queue, New Episodes, played state, positions, playback settings, transcripts,
  bookmarks, playlists and rules sync between the phone and the web player.
- The server checks for new episodes every 15 minutes and can send a "New episode" notification.
- **Android Auto:** browse your queue, New Episodes, playlists and podcasts, with your skip lengths, bookmarks
  and Skip ad. Voice works too: "Hey Google, play <podcast> on Earshot".
- Listening stats, full backup and restore (Settings > Backup), and an in-app update check.

---

## Repository layout

| Folder | What |
|---|---|
| `android/` | The Android app (Kotlin, Jetpack Compose, Media3, Room, WorkManager) |
| `web/` | The web player (React, TypeScript, Vite), deployed to GitHub Pages: see [web/README.md](web/README.md) |
| `supabase/` | Database migrations, edge functions and SQL tests: see [supabase/README.md](supabase/README.md) |
| `shared/test-vectors/` | Test cases that the Android and web tests both check, so the two apps behave the same |
| `.github/workflows/` | Pull request checks, signed Android releases, web deploy, weekly encrypted database backup |

Other docs: [RELEASES.md](RELEASES.md) (signing key, making a release, R8 test build, server backup),
[PROJECT_BRIEF.md](PROJECT_BRIEF.md) (the original design decisions) and
[docs/FRIENDS_INSTALL_MESSAGE.md](docs/FRIENDS_INSTALL_MESSAGE.md) (a draft install message for friends).

---

## Building the Android app

1. Install [Android Studio](https://developer.android.com/studio) with the standard setup (it brings the SDK and a JDK).
2. **File > Open** the `android` folder (not the repository root) and let the Gradle sync finish.
   Versions are pinned in `android/gradle/libs.versions.toml`; ignore offers to upgrade the Android Gradle Plugin.
3. For Podcast Index search and sync, create `android/local.properties` (it's gitignored) with:
   ```
   earshot.supabaseUrl=https\://<project-ref>.supabase.co
   earshot.supabaseKey=<the project's publishable key>
   ```
   Without them the app still works, with Apple's search and no sync.
4. Turn on USB or wireless debugging on your phone and click **Run**. From a terminal:
   `gradlew.bat assembleDebug` (debug APK) or `gradlew.bat testDebugUnitTest` (unit tests).

Release builds are signed and published by GitHub Actions when a `v*` tag is pushed; see [RELEASES.md](RELEASES.md).
To try a shrunk release build next to your installed app, use `gradlew.bat assembleR8test` ("Earshot R8 test", teal icon).

For Android Auto testing, use the Desktop Head Unit: start the head unit server on the phone
(Settings > Android Auto > ⋮ > Start head unit server), then

```
adb forward tcp:5277 tcp:5277
```

```
"%LOCALAPPDATA%\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
```

---

## Versions (checked November 2026)

AGP 9.5.3 · Gradle 9.6.0 · Kotlin 2.4.21 (built in through AGP 9) · KSP 2.3.14 · Compose BOM 2026.11.01 ·
Media3 1.11.1 · Room 2.8.5 · WorkManager 2.12.0 · Coil 3.6.3
