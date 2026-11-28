# Phase 4 test steps

The build is already installed on your Fold6. Your library came through the upgrade intact (I checked).
Everything starts the way you had it: 1.0×, Smart Speed off, Voice Boost off, back 15 s, forward 30 s.

## On the phone

### 1. Smart Speed
1. Open Earshot, tap the mini player to open the player, and play a talk-heavy episode (an interview with natural pauses works best).
2. Listen for about 30 seconds, then tap the **Smart Speed** chip so it lights up.
3. Pauses between sentences should get noticeably shorter, but speech shouldn't sound chopped or clipped.
4. Tap the chip again to turn it off; pauses go back to normal.
5. Tell me if it trims too much, too little, or cuts the starts or ends of words.

### 2. Voice Boost
1. Play a quiet show, or one where the host and guest are at different volumes.
2. Tap the **Voice Boost** chip. After a few seconds the level should rise and even out, without distortion on loud parts.
3. Pause and play at the same volume with it on and off to compare.
4. Tell me if it's too loud, pumps up and down, or adds hiss in quiet moments.

### 3. Time saved
1. With Smart Speed on (and/or speed above 1.0×), play for a minute or two.
2. Under the play button you should see **"Saved Xs on this episode"**, going up as you listen.
3. Open **Settings** (gear icon). The **Time saved** section shows the total, split into "from speed" and "from Smart Speed".

### 4. Skip lengths
1. In the player, tap the forward button. It should jump 30 seconds.
2. **Long-press** the forward button. A list appears (5 seconds to 15 minutes). Pick **1 minute**. The button now says **1m**; tap it and check it jumps 1 minute.
3. Long-press the back button, pick **10 seconds**, and check it.
4. Pull down the notification shade. The notification's skip buttons should match (10 s back icon; forward uses the plain skip icon, since there's no "1 minute" icon).
5. Lock the phone and check the lock-screen controls match too.

### 5. Per-podcast settings
1. In the player, tap the speed chip (**1.0×**). In the Playback dialog, tick **"Just for <this podcast>"**, then pick **1.5×**. Tap Done.
2. Play an episode from a different podcast: it should play at your default speed (1.0×).
3. Go back to the first podcast's episode: 1.5× again.
4. Open that podcast's page (Podcasts tab, tap the show). Tap the **sliders icon** at the top. Speed shows **1.5×**, everything else **Default (…)**. Change Skip forward to 5 minutes, tap Done, and check the player shows **5m** for that show only.
5. To undo: in the player's speed dialog untick "Just for …", and in the podcast's Playback settings set things back to Default.
6. Long-press a skip button on that show: the dialog has its own "Just for …" box for skip lengths.

### 6. Settings defaults
1. Settings > Playback: change Speed, Smart Speed, Voice Boost and the skip lengths. Each applies right away to anything without its own settings, including what's playing.

## In the car (Desktop Head Unit)

1. Phone: **Settings > Android Auto > ⋮ > Start head unit server**.
2. On the VM, in PowerShell:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:5277 tcp:5277
```

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
```

3. If Spotify starts on its own, pause it. Open Earshot in the head unit and play something from the Queue tab.
4. On the now-playing screen, the skip buttons should show your current lengths (for example, 10 s back icon). Tap each one and watch the position jump by that amount.
5. On the phone, long-press a skip button in the Earshot player and pick a different length. The head unit's button should change within a second or two, and the new length should apply when you tap it.
6. Play an episode from a show with its own skip lengths: the car's buttons should switch to that show's lengths.
7. Close the head unit window when done.

## After testing

Tell me how Smart Speed and Voice Boost sounded and whether anything misbehaved. When you're happy, I'll merge the branch into main.
