# Earshot: personal podcast player

Earshot is a podcast app built for one listener and a few friends. It's a native Android app with Android Auto, plus a web player, kept in sync through Supabase. There's no app store listing and no public sign-ups: accounts are created by hand.

- **Latest release:** [v1.0.0](https://github.com/JPollack40/earshot/releases/latest). Download the APK on an Android phone and open it. The app checks for updates itself.
- **Web player:** https://jpollack40.github.io/earshot/

| Folder | What |
|---|---|
| `android/` | Android app and Android Auto: Kotlin, Jetpack Compose, Media3, Room, WorkManager |
| `web/` | Web player: React + TypeScript (Vite), hosted on GitHub Pages |
| `supabase/` | Sync database (SQL migrations), edge functions, and SQL rule tests. See `supabase/README.md` |
| `docs/` | Older phase test steps and notes |
| `shared/test-vectors/` | Cases that both the Kotlin and TypeScript code are tested against, so the two apps follow the same rules |

---

## Features (v1.0)

**Library and discovery**
- Add podcasts by search (Podcast Index, falling back to Apple), feed URL or OPML import. You can preview a show before following it.
- **Browse**: shows picked from what you listen to, top charts by country, categories, opt-in "Popular with your friends", and a "You might like" tab on every show. "Not interested" hides a show for good.
- Pinned podcasts, search inside a podcast, and automatic feed refresh every 2 hours. The server also finds new episodes and can send a push notification.

**Listening**
- **New Episodes → Queue** triage (swipe to queue or archive), a drag-to-reorder queue, and **Continue listening** for episodes you started but didn't queue.
- Smart playlists and rules that sort new episodes automatically.
- **Smart Speed** (shortens silences), **Voice Boost** (evens out loudness), 0.8× to 3× speed, and per-podcast settings with a "time saved" total.
- Skip lengths you choose, a sleep timer, chapters, and a short rewind after a pause.
- Downloads with auto-delete, an optional Wi-Fi-only mode, and a 10-minute streaming buffer plus cache for dead zones.

**Transcripts and AI**
- Feed transcripts when the show provides them, otherwise Deepgram on demand. Search across all transcripts.
- **Bookmarks** keep the transcript around the moment and title themselves. Export as Markdown.
- **Ask about this episode** (answers with tappable timestamps), **find ads**, and a **Skip ad** button that also works in the car.

**Android Auto**
- Queue, New Episodes, Playlists and Podcasts tabs, a speed list, chapters, bookmark, mark played, AI sweep and Skip ad.
- Steering-wheel skip by time, and voice: "Hey Google, play the latest *show* on Earshot".

**Sync and safety**
- Email sign-in. Subscriptions, pins, queue, positions, played state, settings, bookmarks, transcripts and playlists sync between the phone and the web.
- A per-person backup file on the phone, plus a weekly encrypted server backup (`.github/workflows/server-backup.yml`).
- Stats: listening time, streaks and weekly reviews.

---

## Building

**Android** (Windows, Android Studio):
1. Clone the repo and open the **`android`** folder in Android Studio. Let Gradle sync, and don't accept plugin upgrades: versions are pinned.
2. Create `android/local.properties` with the Supabase project URL and publishable key:
   ```
   earshot.supabaseUrl=https\://<project-ref>.supabase.co
   earshot.supabaseKey=sb_publishable_...
   ```
   Without them, search falls back to Apple and sync is off.
3. Build and install with Run ▶, or `gradlew.bat assembleDebug`. Unit tests: `gradlew.bat testDebugUnitTest`. Lint: `gradlew.bat lintDebug`.
4. Push notifications need `android/app/google-services.json` from Firebase. It isn't committed, and the app works without it.
5. `gradlew.bat assembleR8test` builds the shrunk release build signed with the debug key. It installs beside the normal app as "Earshot R8 test" with a teal icon.

**Web:** in `web/`, run `npm ci`, then `npm run dev` (local), `npm test` or `npm run build`. Merging to `main` deploys it to GitHub Pages.

**Server:** apply `supabase/migrations/` in order in the SQL Editor, and deploy each function in `supabase/functions/` through the dashboard editor. Secrets and setup are in `supabase/README.md`. SQL rule tests: in `supabase/tests`, run `npm ci && npm test`.

**Checks:** every pull request runs the web, server and phone tests (`.github/workflows/checks.yml`).

**Releases:** push a tag like `v1.0.1` from `main`. GitHub Actions tests, shrinks and signs the APK and publishes a release. Signing secrets, versioning and the server backup setup are in [`RELEASES.md`](RELEASES.md).

---

## Versions (checked November 2026)

AGP 9.5.3 · Gradle 9.6.0 · Kotlin 2.4.21 (built in through AGP 9) · KSP 2.3.14 · Compose BOM 2026.11.01 ·
Media3 1.11.1 · Room 2.8.5 · WorkManager 2.12.0 · Coil 3.6.3 · React 19.3 · Vite 8.4 · TypeScript 7.0

Android versions are in `android/gradle/libs.versions.toml`, and web versions in `web/package.json`.
