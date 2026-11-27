# Phase 11 test steps: AI from transcripts

Branch `claude/project-thread-wseovk`. Phone database version 9 (adds one table, `episode_ai`; nothing else changes).

**Supabase first:** run `006_ai.sql` and deploy the `ai` function (steps A2 and B2 in `MORNING_README.md`). It uses
your existing `GEMINI_API_KEY`. Check your free limits at https://aistudio.google.com/rate-limit; each task is one
request of about 15,000 to 40,000 tokens for an hour of speech.

Everything here works from an episode's **timed transcript** (a feed transcript with timings, or one made with
Transcribe). It needs you to be signed in, because the function reads the transcript saved in Supabase.

Test episode I suggest: the episode of **Morbid** you transcribed earlier (any episode that has a transcript and
has ad reads). If none of yours has one, open a Morbid episode > **Transcript** > **Transcribe** (about a minute).

## 1. Phone: summary

1. Play the episode. Full player > **AI** chip. A dialog **From the transcript** opens.
2. **Summarise this episode**. The button shows **Working…** (10 to 40 seconds).
3. A few short paragraphs appear, then key moments, each with a time. Tap a time: playback jumps there (and **Back to mm:ss** offers the way back).
4. If it fails, the message at the bottom says why (no transcript, no timings, Gemini's free limit, or Gemini failed). Tell me the exact message.

## 2. Phone: generated chapters

1. Use an episode with **no chapters** (most Morbid episodes). AI > **Make chapters**.
2. After it finishes, a message says **Chapters made from the transcript**, and the player shows **Chapters (N)**. The chapter list header says **· generated from the transcript**.
3. They behave like real chapters: tap to jump, untick to skip.
4. An episode with its own chapters (Podcasting 2.0) says so in the AI dialog and offers no Make chapters button.

## 3. Phone: found ads

1. AI > **Find ads**. A message says **Found N ads** (or **No ads found**). The dialog lists each with its time and length; tap one to jump to it.
2. Default **Skip automatically**: play from a few seconds before an ad: it jumps to where the episode resumes. Go back into the same ad with the scrubber: this time it plays (each ad is skipped once per listen).
3. In the dialog (or **Settings > AI from transcripts > Found ads**) pick **Show a Skip ad button**. Play into an ad: a **Skip ad: <sponsor>** chip shows (player and above the mini player); tap it to jump past.
4. Pick **Off**: ads play normally. Set it back to what you prefer. The choice syncs to the web.

## 4. Android Auto (Desktop Head Unit)

1. Phone: **Settings > Android Auto > ⋮ > Start head unit server**.
2. On the VM, in PowerShell, one after the other:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:5277 tcp:5277
```

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
```

3. If Spotify starts on its own, pause it, then open Earshot.
4. With **Skip automatically** on, play the episode from just before a found ad: the car jumps over it too.
5. Generated chapters are used for chapter skipping in the car. (The Skip ad button is phone and web only: with the button setting, the car plays ads.)
6. Close the head unit window.

## 5. Web player

Refresh first (Ctrl+F5).
1. Play the same episode. Glow: open the **Now playing** panel: a **From the transcript** box shows the summary, chapters and ads made on the phone (shared through Supabase). Classic: ⋯ (More) shows the same under the chapters.
2. Try **Find ads again** or **Summarise again** on the web: it works the same and the phone picks up the new result at its next sync.
3. **Found ads** select: the same three choices, synced with the phone. With the button choice, **⏭ Skip ad: <sponsor>** shows in the player bar during an ad.
4. Generated chapters appear in the web's chapter list (marked "Generated from the transcript"), including for The Changelog-style episodes the web can't read chapters from.

## What to tell me

How good the summaries, chapters and ad times felt (too early or late by a few seconds is worth knowing), and any
error messages word for word.
