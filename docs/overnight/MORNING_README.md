# Overnight build: phases 8, 10, 11 and 9 (2026-11-27)

Everything is on **one branch, `claude/project-thread-wseovk`**, as separate commits stacked in order
(8 → 10 → 11 → 9, then an opt-in phase 12 scaffold). This session is only allowed to push to that one branch, so the phases couldn't go on
separate branches. If you'd like them split later, I can do it once you allow the extra branches.

Nothing was merged to main, nothing was deployed to Supabase, and nothing was installed on your phone.

## Merge and install order (important)

1. **Test and merge phase 7b first** (`claude/project-thread-xwxgyg`, database version 7).
2. Then merge main into this branch (I'll do it), so it contains 7b.
3. Only then install this branch on your phone.

Why: this branch moves the phone database to **version 9**. If a version 9 build is ever followed by an older
build (like 7b's, version 7), Android Room treats it as a downgrade and **starts the library from scratch**.
Installing in the order above avoids that. I back up the phone database before every install, as usual.

## One-time setup you'll do (in this order)

These are all in the Supabase dashboard (project `earshot`), except Firebase and GitHub.

### A. SQL (SQL Editor > New query > paste the whole file > Run). Each is safe to run again.

1. `supabase/migrations/004_listening_upgrades.sql` (phase 10: new columns, push_devices, feed_checks, the new-episode functions)
2. `supabase/migrations/006_ai.sql` (phase 11: the `episode_ai` table)
3. `supabase/migrations/005_schedule_new_episodes.sql`: **only after step B1 below**, and after replacing its two placeholders:
   - `<PROJECT_URL>` → `https://drdpgrdewxcquswuuzfw.supabase.co`
   - `<CRON_SECRET>` → the same random text you saved as `NEW_EPISODES_SECRET` (B1)

To copy a file on the VM without an editor:

```powershell
Get-Content C:\repos\earshot-overnight\supabase\migrations\004_listening_upgrades.sql -Raw | Set-Clipboard
```

### B. Edge functions (Edge Functions > Deploy a new function > Via Editor, name exactly as below, paste, Deploy)

1. **`new-episodes`** from `supabase/functions/new-episodes/index.ts`
   - Secrets (Edge Functions > Secrets): add `NEW_EPISODES_SECRET` = any long random text (e.g. make one with the PowerShell line below). `PODCASTINDEX_KEY` / `PODCASTINDEX_SECRET` are already there.
   - In the function's **Details**, turn **Verify JWT** OFF (the schedule sends its own secret, not a sign-in).
2. **`ai`** from `supabase/functions/ai/index.ts`
   - Uses the `GEMINI_API_KEY` secret you already have. Leave **Verify JWT** ON (the default).
   - Optional secret `GEMINI_MODEL` if you want a different model than `gemini-3.8-flash`.

```powershell
-join ((48..57) + (65..90) + (97..122) | Get-Random -Count 40 | ForEach-Object { [char]$_ }) | Set-Clipboard
```

Then run 005 (A3). To check right away instead of waiting 15 minutes, run in the SQL Editor:

```sql
select public.run_new_episode_check();
```

and a few seconds later:

```sql
select status_code, content from net._http_response order by id desc limit 1;
```

You should see status 200 and something like `{"feeds":42,"lookedUp":42,"checked":42,"found":0,"added":0,...}`.

### C. Firebase (only for "New episode" notifications; everything else works without it)

1. https://console.firebase.google.com > **Create a project** (any name, e.g. "earshot"; Google Analytics off).
2. **Add app > Android**, package name `com.earshot.app`, nickname Earshot. Skip the SHA-1. **Download `google-services.json`** and put it at `C:\repos\earshot-overnight\android\app\google-services.json` (it's gitignored; the build reads it, no Google Services plugin needed). Skip the remaining wizard steps.
3. Project settings (gear) > **Service accounts** > **Generate new private key** > downloads a JSON file.
4. Supabase > Edge Functions > Secrets: add `FCM_SERVICE_ACCOUNT` = the **whole contents** of that JSON file:

```powershell
Get-Content "$env:USERPROFILE\Downloads\<the-file-name>.json" -Raw | Set-Clipboard
```

5. Tell me, and I'll rebuild so the app includes the Firebase settings. Delete the downloaded key file afterwards (the secret keeps it).

### D. GitHub secrets for signed releases (phase 8): **waiting for your OK**

The signing key was made on the VM in `C:\repos\earshot-keys\` (see `RELEASES.md` on the branch). I haven't added
anything to GitHub. When you're ready, add the four secrets yourself (steps in `RELEASES.md`, "One-time: give
GitHub the key"), or reply in the thread "OK to add the release signing secrets to GitHub" and I'll walk the VM
through it. **Back up the key** (the .jks and its password, e.g. in your password manager): if it's lost, friends'
phones can't take updates.

## Test steps

- [PHASE8_TEST_STEPS.md](PHASE8_TEST_STEPS.md): release build and versioning
- [PHASE10_TEST_STEPS.md](PHASE10_TEST_STEPS.md): listening upgrades
- [PHASE11_TEST_STEPS.md](PHASE11_TEST_STEPS.md): AI from transcripts
- [PHASE9_TEST_STEPS.md](PHASE9_TEST_STEPS.md): share with friends, backup and restore
- [FRIENDS_INSTALL_MESSAGE.md](FRIENDS_INSTALL_MESSAGE.md): a draft for you to send friends
- [PHASE12_SCAFFOLD.md](PHASE12_SCAFFOLD.md): custom car screens scaffold (off by default) and the two-icon test that needs your phone
- Updated brief draft: `PROJECT_BRIEF-20261127T120000-overnight.md` (in the main folder)

## Decided overnight: please confirm

1. **One stacked branch** instead of a branch per phase (see top).
2. **Your phone stays on debug builds for now**; release builds are for friends. When you want to switch: Back up everything (phase 9) > uninstall > install the release APK > Restore.
3. **New-episode notifications default to "All podcasts"** (Settings > Notifications: All / Pinned only / Off).
4. **Skipped chapters are per episode** and synced (phone and web). Unticking doesn't carry over to the next episode of the show.
5. **Ad trim is per podcast, off by default**. It only acts when the file is 10 seconds to 20 minutes longer than the feed says.
6. **Server check every 15 minutes**; a feed seen for the first time only treats the last day's episodes as new.
7. **"Back to mm:ss" shows after jumps of 30 seconds or more, for 8 seconds.**
8. **AI ads default to "Skip automatically"**; the other choices are "Show a Skip ad button" and "Off". An ad you go back into after it was skipped plays (it's only skipped once per listen).
9. **AI runs only when you ask** (per episode: Summarise, Make chapters, Find ads). Nothing runs in the background, to stay within Gemini's free limits.
10. **Gemini model `gemini-3.8-flash`** (stable). Whether the free tier still covers text requests needs checking in AI Studio (https://aistudio.google.com/rate-limit); transcription on the free tier failed earlier, so if AI calls get "free limit" errors, billing is the fix (about 2 cents per hour-long episode per task).
11. **Backups are a .zip** with the whole database and this phone's settings. Downloads and the sign-in aren't included (a fresh install signs in after restoring).
12. **Update check**: release builds check GitHub once a day and show a banner; debug builds only check from Settings > Check for updates.
