# Phase 9 test steps: share with friends, backup and restore

Branch `claude/project-thread-wseovk` (phase 9 is the last commit, on top of 8, 10 and 11). No Supabase changes.

## 1. Phone: back up everything

1. **Settings > Backup > Back up everything**. The system's save screen opens with the name `earshot-backup-2026-11-28.zip` (today's date). Save it to **Drive** or **Downloads**.
2. A message says **Backed up N podcasts and M episodes** (expect 42 podcasts and about 14,600 episodes).
3. Optional check on the VM: the file is a normal .zip with `manifest.json`, `earshot.db` and `settings.xml`.

## 2. Phone: restore (safe test on your own phone)

Restoring the backup you just made puts back the same data, so it's a safe test.
1. Change something small first so you can see it come back: unpin one pinned podcast.
2. **Settings > Backup > Restore from a backup** > pick the file from step 1.
3. A dialog: **Restore this backup?** with the date, version and counts. **Cancel** changes nothing. Do it again and tap **Restore and restart**.
4. Earshot closes and opens again by itself. The podcast you unpinned is pinned again, your queue and positions are as at backup time, and downloads you still have show as downloaded.
5. Settings > Sync: still signed in. The next sync pulls everything again (newer changes from the web win; nothing is lost).
6. Pin/unpin back to how you like it.

## 3. Phone: update check

1. **Settings > Updates > Check for updates**. Before any release exists: **No release has been published yet**. After the first release tag (PHASE8 section 3): on your debug build it shows the **Earshot 0.9.0 is available** banner (debug builds count as older than any release).
2. The banner's **Download** opens the APK link in the browser; **What's new** opens the release page; **Not now** hides it until the next version.
3. Don't install that release over your debug build (see section 5).

## 4. Phone: Set up for your car

1. **Settings > Android Auto > Set up for your car**. Six steps with an **Open Android Auto app info** button.
2. Tap the button: Android's app info page for Android Auto opens (from there, "Additional settings in the app").
3. Read through the steps; they're what friends will follow. Tell me anything unclear. (Your phone already has developer mode and Unknown sources on.)

## 5. Moving your phone from debug to release (only when you decide to; not part of tonight's test)

1. Install the latest debug build and let it sync.
2. **Back up everything** to Drive.
3. Back up the database on the VM as usual (I can do this through adb when you hand the phone over).
4. Uninstall Earshot (this deletes downloads).
5. Install the release APK from GitHub Releases.
6. Open it > **Settings > Backup > Restore from a backup** > pick the file > Restore and restart. Then sign in.
7. Android Auto: Earshot may need ticking again in **Customize launcher**.

## 6. Web player

1. Signed out (or in a private window): the sign-in page says **On Android? Get the Earshot app**, linking to the latest release.
2. Signed in: Glow sidebar bottom **⬇ Get the Android app**; Glow at phone width, a **⬇** icon among the top icons; Classic, **Android app** in the top bar. All open https://github.com/JPollack40/earshot/releases/latest.

## 7. Friends (when you're ready)

1. Create each friend's account: Supabase > Authentication > Users > **Add user** > email + password, **Auto Confirm User** ticked.
2. Make a release (PHASE8 section 3).
3. Send them the message in `FRIENDS_INSTALL_MESSAGE.md` (edit as you like).
