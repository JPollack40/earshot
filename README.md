# Earshot: personal podcast player

*(Earshot is a working name. Changing it later takes about two minutes.)*

One repo for the whole project:

| Folder | What | Phase |
|---|---|---|
| `android/` | Native Android app (Kotlin, Jetpack Compose, Media3, Room) | 1 onward |
| `web/` | Web player (React + TypeScript) | 5 |
| `supabase/` | Sync database and edge functions (Podcast Index and transcription proxies) | 5 |

---

## What's in this build (phases 1 to 3)

- **Add podcasts** by searching, pasting a feed URL, or importing an OPML file from your current app.
- **Latest tab** shows new episodes from all shows, newest first. **Podcasts tab** shows your shows with unplayed counts.
- **Compact episode rows**: artwork, title, date, duration or time left, progress bar, download and play buttons.
  Tap a row for show notes, download, and mark played or unplayed.
- **Background playback** with lock-screen and notification controls and headset buttons.
  Pauses when headphones are unplugged.
- **Remembers your place** in every episode. Position is saved every 5 seconds and on pause.
- **Player screen**: scrubber, skip back 15 s and forward 30 s, speed from 0.8× to 3×, a sleep timer (minutes or end of episode),
  and chapters from Podcasting 2.0 feeds (tap a chapter to jump to it).
- **Downloads** run in the background with a progress notification. They retry automatically 3 times,
  then show a red retry icon instead of failing silently. Downloaded episodes play from the file.
- **Automatic feed refresh** every 2 hours, plus the refresh button.
- **Download auto-delete and Settings** (phase 2): finished downloads are removed, unfinished ones expire after a chosen number of days.
- **Inbox → Queue triage** (phase 3): new episodes land in the Inbox. Swipe right to queue, left to archive, or Archive all.
  The Queue plays top to bottom: drag the handle to reorder, swipe left to remove. Playing an episode moves it to the top,
  and when it finishes the next one starts. Queued episodes download automatically (Settings toggle).
- **Unsubscribing keeps queued episodes** until you play or remove them.
- **Android Auto** (phase 3): browse Queue, Inbox, Latest and Podcasts in the car, with back 15 / forward 30 buttons.

Not in this build yet: Smart Speed and Voice Boost (phase 4), sync and the web player (phase 5), transcripts (phase 6).


---

## One-time setup (Windows)

### 1. Install Android Studio
1. Download it from **developer.android.com/studio** and run the installer with the default options.
2. On first launch, pick **Standard** setup. This downloads the Android SDK (about 3–5 GB).
3. If it asks about a JDK, use the bundled one.

### 2. Put the project somewhere permanent
Unzip `earshot.zip` to, for example, `C:\dev\earshot`.

### 3. Open the Android app
1. Android Studio: **File → Open**, then select the **`android`** folder inside `earshot`, not the top `earshot` folder.
2. Wait for the **Gradle sync** to finish (bottom status bar). The first sync downloads the build tools and libraries,
   which takes 5–10 minutes.
3. If Android Studio offers to **upgrade the Android Gradle Plugin**, click **Don't remind me** for now. The versions are pinned on purpose.
4. If it asks you to install a missing SDK platform (Android 16 / API 36), accept.

### 4. Set up your phone
1. **Settings → About phone →** tap **Build number** 7 times. This unlocks Developer options.
2. **Settings → System → Developer options →** turn on **USB debugging**.
3. Plug the phone into the PC and accept the "Allow USB debugging?" prompt on the phone. Tick "Always allow".
4. Your phone's name appears in the device dropdown at the top of Android Studio.

Wireless alternative: in Developer options, turn on **Wireless debugging**, then use Android Studio's
**Device Manager → Pair devices using Wi-Fi**.

### 5. Run it
Click the green **Run ▶** button. The app installs and opens on your phone. Allow notifications when it asks,
since the player controls and download progress use them.

### 6. Create the GitHub repo (no editor needed)
1. On github.com, create a new **private** repo named `earshot`. Leave it empty: no README and no .gitignore.
2. In a terminal (PowerShell or Git Bash) inside `C:\dev\earshot`:
   ```
   git init
   git add .
   git commit -m "Phase 1: core player"
   git branch -M main
   git remote add origin https://github.com/<your-username>/earshot.git
   git push -u origin main
   ```

---

## Things to try in phase 1

1. **Add → Import OPML**: export your subscriptions from Pocket Casts, Podcast Addict or another app and import the file.
2. Search for a show, subscribe, and play an episode. Lock the phone and check that the notification controls work.
3. Play partway, switch to another episode, then come back. It should resume where you left off.
4. Download an episode, turn on airplane mode, and play it.
5. For chapters, try a Podcasting 2.0 show (for example *Podcasting 2.0* by Adam Curry and Dave Jones).

If something breaks, copy the red error text from **Build** (sync or compile problems) or **Logcat** (crashes on the phone)
and paste it to Claude.

---

## Versions (checked November 2026)

AGP 9.5.0 · Gradle 9.6.0 · Kotlin 2.4.20 (built in through AGP 9) · KSP 2.3.13 · Compose BOM 2026.11.01 ·
Media3 1.11.1 · Room 2.8.5 · WorkManager 2.12.0 · Coil 3.6.3

All versions are in `android/gradle/libs.versions.toml`.
