# Earshot: notes for Claude

Earshot is Serena's personal podcast player: a native Android app with Android Auto, a React web player,
and Supabase for sync, accounts and server work. Single owner plus a few friends; no store listing.
Product decisions, phase plan, status and open issues live in **PROJECT_BRIEF.md** (in Claude project
threads, the newest `PROJECT_BRIEF-*.md` in the project files is more current). Read the part you need
there; don't copy it here. Treat its decisions as settled unless Serena changes them.

## Module map

**`android/`**: one Gradle module `app`. Code namespace `com.earshot.app`; **application ID
`com.jpollack.earshot`** (use it in every `adb` / `run-as` command).
- `EarshotApp.kt`: Application; WorkManager, crash handler, push setup.
- `playback/PlaybackService.kt`: Media3 `MediaLibraryService`: the player, media session, **Android Auto
  browse tree** (`onGetLibraryRoot` / `onGetChildren`), custom car buttons and voice search
  (`onSetMediaItems`). Also `SmartAudioChain.kt` + `VoiceBoostProcessor.kt` (Smart Speed / Voice Boost),
  `PlayerConnection.kt` (UI ↔ service), `StreamCache.kt`, `ListeningLog.kt`.
- `ui/`: `MainActivity.kt`, `AppViewModel.kt`, `screens/` (Compose screens, one file per area).
- `data/`: `Repository.kt` (the app's main API), `db/` (Room: `AppDatabase.kt` holds `VERSION` and every
  Migration, `Entities.kt`, `Daos.kt`), `sync/` (`SyncEngine.kt`, `Supabase.kt`), `feed/` (RSS, OPML,
  chapters), `ai/`, `transcript/`, `browse/`, `search/`, `stats/`, `RemoteConfig.kt` (reads `app_config`),
  `Updates.kt` + `SelfUpdate.kt` (update check, beta ring, one-tap install), `CrashReports.kt`, `Backup.kt`.
- `work/`: WorkManager workers (refresh, download, cleanup, transcribe, AI sweep, bookmark titles).
- `push/`: FCM. `res/xml/automotive_app_desc.xml` + the manifest declare the Android Auto media app.
- Build types: `debug` (Serena's phone), `release` (signed in CI), `r8test` (shrunk, installs beside as
  "Earshot R8 test"), `updatetest` (side-by-side app for one-tap update tests, see `android/tools/`).
- Tests: `app/src/test/java/com/earshot/app/**` (JVM unit tests).

**`web/`**: React + TypeScript (Vite), GitHub Pages. `src/main.tsx` → `App.tsx`; `store.ts` (state + sync),
`supabase.ts`, `player.ts` (audio, Smart Speed / Voice Boost approximations), `feed.ts`, `screens/`.
Tests in `web/tests/` (Vitest, mostly the shared vectors).

**`supabase/`**: `migrations/NNN_*.sql` (run by hand, in order, in the SQL Editor; RLS on every table),
`functions/<name>/index.ts` (Deno edge functions: `podcast-search`, `feed-proxy`, `transcribe`, `ai`,
`browse`, `new-episodes`; deployed by pasting into the dashboard editor), `tests/` (sync rules in PGlite).
See `supabase/README.md`.

**`shared/test-vectors/`**: JSON cases both Kotlin and TypeScript tests run, so phone and web follow the
same rules (feed keys, keywords, mark range, playlists).

## Build, lint, test

Android builds only run on Serena's Windows VM (`C:\repos\earshot`); the cloud can't reach Google's SDK
servers. From Git Bash on the VM, in `android/`:

```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # not set on the VM
./gradlew assembleDebug        < /dev/null   # debug APK (2 to 6 min; ~30 min with a cold cache)
./gradlew testDebugUnitTest    < /dev/null   # unit tests (what CI runs)
./gradlew lintDebug            < /dev/null   # lint, expect 0 errors
./gradlew assembleR8test       < /dev/null   # shrunk build, installs beside the real app
```

**Always close Gradle's stdin (`< /dev/null`) when it runs from a tool or script, or it can hang.**
From PowerShell use `.\gradlew.bat ...`. `android/local.properties` (gitignored) holds the Supabase URL and
publishable key.

Web, in `web/`: `npm ci`, `npm test`, `npm run typecheck`, `npm run build` (typecheck + Vite).
On the VM, Node may need `$env:Path = "C:\Program Files\nodejs;$env:Path"`.
Server, in `supabase/tests/`: `npm ci && npm test`.
CI (`.github/workflows/checks.yml`) runs web, server and phone unit tests on every PR.

## Rules that bite

- **AGP 9 has built-in Kotlin: never add the `org.jetbrains.kotlin.android` plugin.** Versions are pinned
  in `android/gradle/libs.versions.toml` and `web/package.json`; don't accept upgrades casually.
- **Room changes need a real Migration** (and a `VERSION` bump). The destructive fallback would wipe
  Serena's library. Her pinned podcasts and library must survive every upgrade.
- Back up the phone database before installing a build (see the `device-test` skill).
- Never use Serena's phone without her written OK in the working thread naming the step.
- The repo is public: no secrets, keys, personal data or private hostnames in commits.
- Edge functions and SQL are deployed by hand: say exactly what to paste where.

## Releases

Tag-driven: `vX.Y.Z` = stable, `vX.Y.Z-beta.N` = beta pre-release. CI derives the version code from the
tag (MAJOR×1,000,000 + MINOR×10,000 + PATCH×100 + n, n = beta number or 99 for stable), so there's no
manual bump. Beta ring, stable rhythm, `app_config` kill switch / `hold_version` and one-tap update are in
**RELEASES.md**. Use the `release` skill.

## Working efficiently (read this)

Long threads cost most by re-reading a big context on every tool call. Keep this thread's context small.

- **Delegate reading-heavy work to subagents** and keep only their short reports:
  - `code-reviewer`: review ONE area or diff. For a big review, run several in parallel, one per area,
    then verify each finding yourself before acting.
  - `build-doctor`: run a Gradle build / lint / tests or the web build and get back only distinct errors.
    Never pull a raw build log into the main thread.
  - `researcher`: feature ideas and best practices, returns a ranked one-page shortlist with links. Use
    it instead of running research on Fable (or any big model) in the main thread.
  - Built-in **Explore** agent: broad "where is X / how does Y flow" searches across many files.
- **Read only the files you need.** Use Grep / Glob to find the spot, then read that range, not the
  whole file. Don't re-read files you already have.
- Skills: `device-test` (phone + Android Auto DHU), `release` (cut a release), `phase-handoff`.
- **When a phase is done, run the `phase-handoff` skill and finish the thread** instead of carrying on
  into the next phase. The next thread starts from `docs/handoffs/<phase>.md`, not from this history.

## Working with Serena

- One follow-up question at a time; multiple choice with the recommended option first.
- Complete drop-in files, never fragments. Command-line git steps; no VS Code (IDE is Android Studio).
- Verify versions and APIs against official docs. Make test steps concrete (named shows and episodes),
  and always include the DHU launch commands for Android Auto steps.
- She does all communication with friends; Claude only drafts.
