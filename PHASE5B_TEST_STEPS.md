# Phase 5b test steps: sync, web player, pins, "New Episodes"

The new build is on your Fold6 and you're signed in. Your library came through the database upgrade intact (40 podcasts, 10 queued, 2 new; backup in `C:\repos\earshot-backups\pre-phase5b-…`). The first sync uploaded 1,155 items.
The web player is at **https://jpollack40.github.io/earshot/**

Sync timing: the phone uploads about 10 seconds after a change and checks for changes whenever you open or return to the app. The web player checks every 30 seconds, when you come back to its tab, and when you click **⟳**.

## 1. Phone: Settings and sign-in
1. Earshot > **gear**. The **Sync** section at the top says "Signed in as …" and "Last synced …" with today's time.
2. Tap **Sign out**. The section becomes a **Currently signed out** row with a **Sign in** button on the right.
3. Tap **Sign in**. Type your password, then tap the **eye icon** to show it, and again to hide it. Tap **Sign in**. It says "Signed in and synced".

## 2. Phone: pins and "New Episodes"
1. The second tab is called **New Episodes**.
2. **Podcasts** tab: tap the **pin icon** on **You're Wrong About**, then on **Behind the Bastards**. Both jump to the top in alphabetical order (Behind the Bastards, then You're Wrong About), with everything else alphabetical below.
3. Open **Morbid** > **⋮**: there's a **Pin to top** option too.

## 3. Web player: first look
1. In a browser (on the VM or any computer), open https://jpollack40.github.io/earshot/ and sign in. Try the **Show** button next to the password.
2. **Queue 10**: same episodes in the same order as the phone, with **Octomom** at the top.
3. **New Episodes 2** and **Podcasts 40**: Behind the Bastards and You're Wrong About are pinned at the top.

## 4. Sync, phone to web
1. Phone, **Queue** tab: drag **Brutal Murder of Ian Huntley** (The Crime Agents) to the top. Wait about 10 seconds.
2. Web: click **⟳** (top right). It's now at the top of the Queue there too.

## 5. Sync, web to phone
1. Web, **Podcasts** tab: click the 📌 on **Morbid**. It moves up among the pinned ones.
2. Phone: switch to another app and back to Earshot (that triggers a sync). **Morbid** is now pinned on the phone.
3. Phone: unpin Morbid. After about 10 seconds, click ⟳ on the web: it's unpinned there too.

## 6. Play position follows you
1. Web, **Queue**: play **Episode 4: Could It Be?** (CounterClock) for about a minute, then pause.
2. Phone: switch away from Earshot and back. That episode's "left" time is about a minute shorter. Play it, and it continues near where the web stopped.

## 7. Web player controls
1. Play anything. Try the **↺ / ↻** skip buttons, which use your skip lengths (back 10 s, forward 1 min for you right now), and drag the scrubber.
2. **Speed** menu: pick 1.5×. Then try the **Smart Speed** and **Voice Boost** chips. If they're greyed out, that episode's host doesn't let browsers process its audio. Hover over them to see why. Speed still works.
3. **⋯** opens skip lengths, the sleep timer (minutes or end of episode), and chapters. For chapters, open **Podcasting 2.0** under Podcasts and play its newest episode.
4. The **space bar** plays and pauses.

## 8. Web: search, look before subscribing
1. **Podcasts** > **Behind the Bastards** > 🔍 > `kissinger`. About 9 episodes match, each with the matching show-notes text.
2. **＋** > search `radiolab` > click **Radiolab**. You see all its episodes and a **Subscribe** button. Leave without subscribing.

## 9. In the car (Desktop Head Unit)
1. Phone: **Settings > Android Auto > ⋮ > Start head unit server**.
2. On the VM, in PowerShell, run these one after the other:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:5277 tcp:5277
```

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
```

3. If Spotify starts on its own, pause it, then open Earshot.
4. The tabs read **Queue, New Episodes, Latest, Podcasts**. Pinned podcasts come first in the Podcasts tab.
5. Close the head unit window when you're done.

## Undo afterwards (if you like)
Unpin anything you pinned only for testing, and drag the queue back into your preferred order. Either device works, since the other follows.

## What to tell me
- Anything that didn't sync, with what you changed, where, and roughly when.
- Episodes where Smart Speed and Voice Boost were greyed out on the web (show names), so I can see how common it is.
