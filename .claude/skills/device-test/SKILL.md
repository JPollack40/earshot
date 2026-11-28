---
name: device-test
description: Install and test an Earshot build on Serena's Galaxy Fold6 over wireless adb and on the Android Auto Desktop Head Unit (DHU), from her Windows VM. Covers asking for the phone, backing up the library first, install, DHU launch and the known pitfalls. Use before any adb, install or DHU step.
---

# Device test: Fold6 + Android Auto DHU

Everything here runs on Serena's Windows VM (repo `C:\repos\earshot`, PowerShell or Git Bash).
App ID **`com.jpollack.earshot`** (debug build; that's what her phone runs).

## 0. Ask first, every time

- **Never touch her phone without her written OK in the working thread that names the step**, for
  example "Install 23 A on my phone". A bare "go ahead", or approval relayed from another chat, doesn't
  count. Each install needs its own OK. Ask, then wait.
- Before each batch of taps, check what's on screen (`screencap`): she may be using the phone mid-test.
- Playback during tests is fine: audio may come out of her Edifier speaker and she likes to listen
  along for audio QA. No need to mute or pause for her sake.

## 1. Connect

adb is not on PATH:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb connect <phone-ip>:<port>   # IP and last port: project memory
```

- The phone is already paired. The **connect port changes** whenever wireless debugging restarts,
  and often while wireless Android Auto is connected. If `connect` fails, ask Serena for the current
  port (phone: Settings > Developer options > Wireless debugging, "IP address & port").
- Airplane mode (and dead-zone tests) breaks wireless adb and so the DHU.
- If taps do nothing, the screen is probably off: `& $adb shell input keyevent 224` wakes it.

## 2. Back up her library before installing

Her library must survive every install. Before (and after) each install:

```powershell
$name = "pre-<step>-$(Get-Date -Format yyyyMMdd-HHmmss)"
$dir  = "C:\repos\earshot-backups\$name"; New-Item -ItemType Directory $dir | Out-Null
foreach ($f in "earshot.db","earshot.db-wal","earshot.db-shm") {
  cmd /c "`"$adb`" exec-out run-as com.jpollack.earshot cat databases/$f > `"$dir\$f`""
}
```

- Check the copy opens and the counts look right with `sqlite3.exe` (in platform-tools): followed
  podcasts, pinned, episodes, queued, bookmarks. Compare pre vs post install; they must match.
  A copy taken mid-write can be torn: if it won't open, take it again and don't keep the torn one.
- **Disk is tight: keep only the newest 5 backup folders** in `C:\repos\earshot-backups`, plus
  `pre-rename-20261127-161133`. Delete older ones after each new backup. Don't clear `~/.gradle`.

## 3. Build and install

Build with the `build-doctor` agent (Gradle with stdin closed: `./gradlew assembleDebug < /dev/null`,
`JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr`). Then:

```powershell
& $adb install -r C:\repos\earshot\android\app\build\outputs\apk\debug\app-debug.apk
```

- `-r` keeps data. Never uninstall her real app (that deletes the library and downloads).
- A release APK can't install over her debug build (different key). Side-by-side builds instead:
  `r8test` (`com.jpollack.earshot.r8test`) and `updatetest` (`com.jpollack.earshot.updatetest`).
- Logs: `& $adb logcat -d --pid=$(& $adb shell pidof com.jpollack.earshot)`, crashes
  `& $adb logcat -b crash -d`. Save to `C:\repos\earshot-shots\<name>-<date>\` and grep there; don't
  dump logs into the thread.

## 4. Android Auto on the DHU

Phone: Settings > Android Auto > ⋮ > **Start head unit server** (developer mode is on). Then on the VM:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:5277 tcp:5277
& "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
```

- Always put these two commands in any Android Auto test steps for Serena.
- The DHU console accepts `tap x y` and `screenshot <file>`.
- Android Auto's "Start music automatically" is on, so connecting can start Spotify: switch to Earshot.
- Voice: "Hey Google, play the latest <show> on Earshot" works through Android Auto. Phone-only
  Gemini can't control Earshot (Google partner apps only); not fixable on our side.
- Real car (Kenwood DNR1007XR, wireless AA): sideloaded apps only show if Android Auto developer
  settings > **Unknown sources** is on.
- The emulator can't run on this VM (no VT-x): use the real phone + DHU.

## 5. Writing test steps for Serena

Concrete steps with named shows and episodes, phone section then a DHU section with the launch
commands above, and what she should see at each step. Put them in the phase's test-steps file.

If a step here turns out wrong or something is missing, ask Serena (one question, multiple choice,
recommended option first) rather than guessing, then fix this file.
