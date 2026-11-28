# Phase 5 (part 1) test steps

The build is already installed on your Fold6, and your library came through intact (40 podcasts, 9 queued, 1 in the Inbox, same as before; backup in `C:\repos\earshot-backups`).
I already checked on the phone: a "radiolab" search came from Podcast Index, Radiolab opened without subscribing, searching it for "octopus" found 5 of 680 episodes, and backing out left nothing behind.

## On the phone

### 1. Search uses Podcast Index
1. Tap **+** (top right of the Library).
2. Type `radiolab` and press search.
3. Under the search box it should say **"From Podcast Index. Tap a podcast to look before subscribing."**
4. The first result is **Radiolab, WNYC Studios • 680 episodes**. Most results now show an episode count.
5. Search `behind the bastards`. The first result (Cool Zone Media and iHeartPodcasts) shows a **check mark** because you're subscribed. The second one, by "Behind the Bastards" on Spreaker, is a separate copy of the feed, so it offers Subscribe. That's expected.

### 2. Look at a podcast without subscribing
1. Search `radiolab` again and tap the **Radiolab (WNYC Studios)** row itself, not its Subscribe button.
2. Its page opens with a **Subscribe** button and the line "Not subscribed. Episodes you play or queue stay in your queue until played."
3. Scroll: all 680 episodes are there, newest first ("Terminal Delirium", Nov 20, at the top).
4. Tap the **Octomom** row dated **Oct 23** to open its sheet, then tap **Play last**.
5. Press back until you're on the Library.
6. **Queue** tab: Octomom is now at the bottom (10 episodes). **Podcasts** tab: still 39, with no Radiolab in the list. **Inbox** is unchanged.
7. Leave Octomom there for the car test below. When it's played (or you swipe it off the queue), Radiolab disappears completely.

### 3. Search inside a podcast
1. On Radiolab's page (open it from search again), tap the **magnifying glass** at the top.
2. Type `octopus`. It should say **"5 of 680 episodes match"**: three "Octomom" entries, "The Cage" and "Terrestrials: The Mastermind". Under each one is the bit of the show notes that matched.
3. Clear it with the **X** and type `"black hole"` **with the quotes**. You should get about 4, including "Little Black Holes Everywhere". Without the quotes, it finds episodes that mention "black" and "hole" anywhere.
4. Type `crispr` and you get 3, including "Antibodies Part 1: CRISPR".
5. Back arrow (or the phone's back) closes the search first, then the page.
6. Now try a show you're subscribed to: **Podcasts** tab, **Behind the Bastards**, magnifying glass, `kissinger`. You should get about 9 of 1,210. Then try `disney`, which should give about 3.

### 4. Subscribe from the preview
1. **+**, search `99% invisible`, and tap the **99% Invisible** row (Roman Mars).
2. Tap **Subscribe** on its page. A message says "Subscribed to 99% Invisible" and the "Not subscribed" line goes away.
3. Back to the Library: **Podcasts** goes to 40, and the **Inbox** gains just one episode (its newest).
4. If you don't want it, open it, then **⋮**, then **Unsubscribe**.

## In the car (Desktop Head Unit)

1. Phone: **Settings > Android Auto > ⋮ > Start head unit server**.
2. On the VM, in PowerShell, run these two commands one after the other:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:5277 tcp:5277
```

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
```

3. If Spotify starts on its own, pause it, then open Earshot in the head unit.
4. **Queue** tab: Octomom (Radiolab) is at the bottom and plays from there.
5. **Podcasts** tab: Radiolab is **not** listed (it's only in your queue, not subscribed).
6. Close the head unit window when you're done.

## What to tell me
- Anything that looked wrong, with the show and search words you used.
- Whether the matched show notes line under each search result is helpful or too busy.
