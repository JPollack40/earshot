# Phase 12 scaffold: custom car screens (not for testing yet)

Branch `claude/project-thread-wseovk`. **Off by default**: normal builds, Android Studio and the release workflow
build exactly the app you know. The scaffold only appears when a build adds `-Pearshot.carApp=true`.

## What's there

- A Car App Library **templated media app** (Google calls it Beta; it needs Car App Library API level 8):
  `android/app/src/carapp/` (service `EarshotCarAppService`, a Queue screen, a playback screen).
  Car App Library 1.7.0 (stable, checked 2026-11-27).
- The normal media service stays and still plays everything (Google requires it). The car host draws the
  playback controls from its session; the playback service now shares its session token for that.
- Three versions of the app when the switch is on:
  - **standard**: today's app, no custom screens.
  - **twoIcons**: today's app (same id, installs over your debug build) plus the custom car app. This is
    the **first test**: does Android Auto show two Earshot icons, one, or only the custom one?
  - **lab**: **Earshot Lab** (`com.earshot.app.lab`), a separate app beside Earshot with the custom car app.
    Your library reaches it through sync (sign in once). This is the fallback you suggested if two icons
    don't work, and it's ready to use without touching the main app.

## The two-icon test (needs your phone, so it waits for you)

Build on the VM (I can do this):

```powershell
cd C:\repos\earshot-overnight\android
.\gradlew.bat assembleTwoIconsDebug -Pearshot.carApp=true
```

Then, with your OK to use the phone, install `app\build\outputs\apk\twoIcons\debug\app-twoIcons-debug.apk`
(it replaces your debug app; same library, nothing lost), and open the Desktop Head Unit:

1. Phone: **Settings > Android Auto > ⋮ > Start head unit server**.
2. On the VM, one after the other:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:5277 tcp:5277
```

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
```

3. Count the Earshot icons in the app launcher, and open each. Then install the normal debug build again.

If only one icon shows, the fallback is **lab** (`assembleLabDebug -Pearshot.carApp=true`), installed beside Earshot.

## Not done yet

The custom screens are placeholders (queue list and Google's playback screen). Designing them is the phase itself.
The scaffold lets any car host connect (fine for testing; an allowlist goes in before friends get it).
