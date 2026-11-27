# Phase 10 test steps: listening upgrades

Branch `claude/project-thread-wseovk`. **Before installing:** merge order in `MORNING_README.md` (7b first). The phone
database goes from version 7 to 8 here (and 9 with phase 11): new columns only, nothing removed. I back up the
database before installing, as always.

**Supabase first:** run `004_listening_upgrades.sql`, deploy `new-episodes`, then run `005` (steps A and B in
`MORNING_README.md`). Until 004 is run the phone and web still work and sync; the new synced things (skipped chapters,
ad trim, badges) just stay on the device that set them.

Test podcasts I suggest (subscribe, unsubscribe at the end if you like):
- **Podcasting 2.0** (Adam Curry and Dave Jones): JSON chapters with artwork, hosts and guests, a funding link.
- **The Changelog: Software Development, Open Source** by **Changelog Media** (not the Screenly one): chapters embedded in the MP3 file.
- **Serial**: season and episode numbers.

## 1. Phone: undo a jump

1. Play any episode at least 2 minutes long, from the start.
2. Open the full player and drag the scrubber far ahead (5 minutes or more). A chip **Back to 0:xx** appears under the scrubber for about 8 seconds.
3. Tap it: playback returns to where you were.
4. Small jumps don't offer it: tap the skip-forward button. No chip.
5. Jump again, close the player: the chip shows above the mini player too.

## 2. Phone: chapters, chapter artwork and links, skipping chapters

1. Play the latest **Podcasting 2.0** episode. Open the full player: the artwork changes with each chapter (where the chapter has its own image) and a link icon shows next to the chapter title when a chapter has a link. Tap it: the link opens in the browser.
2. **Chapters (N)** chip > the chapter list: each row has a tick box, a thumbnail and a link button.
3. Untick the chapter **after** the one playing. The header says **· 1 skipped**. Tap the current chapter's start, then skip forward to near its end: when it ends, playback jumps over the unticked chapter.
4. Untick the **last** chapter, then play into the one before it: when it ends, the episode finishes (marked played) and the next queued episode starts.
5. Tick them again.
6. Embedded chapters: play the latest **The Changelog** episode (streaming is fine). After a few seconds, **Chapters (N)** appears, read from the MP3 file itself. Downloaded episodes are read from the file on the phone.

## 3. Phone: Podcasting 2.0 extras and sorting

1. Open **Podcasting 2.0**'s page: under the title, **Hosts: Adam Curry, Dave Jones** (or similar), and a **Support this show** button (or the feed's own wording) that opens their funding page.
2. Tap an episode: its sheet shows the people on that episode, if the feed lists them.
3. Open **Serial**: episode rows show badges like **S4 E3**. The **⋮** menu has **Sort: oldest first** and **Sort: by episode number** (season, then episode). A note under the title shows the order; tap it to go back to newest first.
4. A finished show (one whose feed says it's complete, e.g. S-Town if its feed marks it) shows **Finished show: not checked for new episodes automatically** on its page. If no show of yours says it, that's fine.

## 4. Phone: trim inserted ads

Works for shows whose files are longer than the length the feed states, because ads are stitched on. I suggest **Morbid**.
1. Morbid's page > pull down to refresh first (episodes added before this build don't know the feed's length until then), then **⋮ > Trim inserted ads** > **At the start** > OK. The page shows a note about the trim.
2. Play a Morbid episode you haven't started, from the beginning. If the file is 10 seconds to 20 minutes longer than the feed says, playback jumps straight past the start and a message says **Skipped m:ss of inserted ads at the start**. If the lengths match, a message says **no inserted ads found** with both lengths, which is also a correct result: tell me which.
3. Set it back to **Off** (or keep it if it worked).

## 5. Phone: notifications for new episodes (only if you did Firebase, step C)

1. Rebuild with `google-services.json` in place (I'll do it), install, open the app once and allow notifications.
2. **Settings > Notifications > New episodes**: All podcasts (default), Pinned only, Off.
3. Next time a subscribed show publishes, within 15 minutes a notification **New episode** shows with **Play**. Tapping Play opens Earshot and plays it.
4. Without Firebase, the setting says it isn't set up, and nothing else changes.

## 6. Server check (no phone needed)

1. After 005 is running, in the SQL Editor:

```sql
select feed_url, pi_feed_id, to_timestamp(checked_since) as since, last_error from public.feed_checks order by feed_url;
```

Every subscribed feed should be listed, most with a `pi_feed_id`.
2. When a show publishes, the web player's **New Episodes** shows it within 15 minutes, even with the phone off.

## 7. Car voice (Desktop Head Unit)

1. Phone: **Settings > Android Auto > ⋮ > Start head unit server**.
2. On the VM, in PowerShell, one after the other:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:5277 tcp:5277
```

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
```

3. If Spotify starts on its own, pause it, then open Earshot.
4. In the DHU, press the microphone (or type in its console) and say: **"Play podcasts on Earshot"**: it resumes what you were listening to (or the top of the queue).
5. **"Play the latest Morbid on Earshot"**: plays Morbid's newest unplayed episode.
6. A loose title: **"Play Behind the Bastard on Earshot"** (singular): still finds Behind the Bastards.
7. Skipped chapters and ad trim also work in the car: play the Podcasting 2.0 episode with a chapter unticked and watch it jump.
8. Close the head unit window.

## 8. Web player (https://jpollack40.github.io/earshot/ after merging; or tell me and I'll describe a local run)

Refresh first (Ctrl+F5).
1. Undo a jump: drag the scrubber far ahead: **↶ Back to m:ss** shows for 8 seconds.
2. Podcasting 2.0 episode: the chapter list (Now Playing panel, or ⋯ in Classic) has tick boxes, thumbnails and links. A chapter unticked on the phone shows unticked here, and is skipped here too.
3. Podcasting 2.0's page: hosts, **Support this show**, finished note where it applies. Serial: badges and the **Sort** select.
4. Morbid's page > settings panel > **Trim inserted ads**: the choice made on the phone shows here.
5. The web doesn't read chapters embedded in MP3 files (The Changelog shows none on the web). Phase 11's generated chapters fill that gap.

## What to tell me

Anything that didn't happen as written, with the show and episode. For ad trim, whether Morbid jumped.
