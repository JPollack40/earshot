---
name: release
description: Cut an Earshot release: pick beta vs stable, check the version code and app_config, write the RELEASES / What's-new notes, push the tag from the VM and confirm the one-tap update. Use whenever a vX.Y.Z or vX.Y.Z-beta.N tag is being planned or pushed, or a bad release must be held.
---

# Release

Source of truth: **RELEASES.md** (signing, rings, rhythm, kill switch) and
`.github/workflows/android-release.yml`. Read the section you need there; this skill is the checklist.

## 1. Beta or stable?

- **Beta** `vX.Y.Z-beta.N` (N = 1 to 98): ring 1 friends with Settings > Updates > Get beta updates on.
  Published as a GitHub **pre-release**, never "latest". Normal path for every batch of merged fixes.
- **Stable** `vX.Y.Z`: everyone. Tag the **same commit** as the last good beta, after at least 7 days in
  beta with no new crash reports (Supabase > Table Editor > `crash_reports`). Every 4 to 6 weeks.
- Off-cycle stable only for a crash, data-loss or security fix.
- Ask Serena which, if she hasn't said (recommend beta).

## 2. Version code (no manual bump)

CI derives it from the tag: MAJOR×1,000,000 + MINOR×10,000 + PATCH×100 + n, n = beta number or 99 for
stable (`v1.2.0-beta.1` = 1020001, `v1.2.0` = 1020099). There's nothing to edit in Gradle. Check:
- the new code is higher than every published release (Android refuses downgrades);
- minor and patch are ≤ 99, beta number ≤ 98;
- the tag doesn't already exist (`git ls-remote --tags origin`).

## 3. Before tagging

- `main` is green: Checks workflow passed on the last merge.
- Any SQL migration or edge function the build needs is already applied / deployed in Supabase (she
  does this by hand from the dashboard: say exactly what to paste where).
- **`app_config` check** (Supabase > Table Editor > `app_config`, one row): `ai_on`, `ad_finding_on`,
  `transcribe_on`, `browse_ai_on` as intended; `banner` empty unless wanted; `hold_version` empty
  (or it will hide this release); `min_supported_version` only raised on purpose (it shows a
  non-dismissable "Please update" warning to older release builds; debug builds ignore it).
- Release notes: 3 to 6 plain bullets grouped New / Fixed / Changed. Draft them for Serena.
- Optional dry run: Actions > **Android release** > Run workflow on the branch (APK kept 90 days).

## 4. Tag and push (from the VM)

The cloud container can't push tags (proxy 403), so this runs on Serena's VM, and pushing a tag needs
her written OK in the thread naming it (e.g. "Push tag v1.2.0-beta.1").

```
cd C:\repos\earshot
git checkout main
git pull
git tag v1.2.0-beta.1
git push origin v1.2.0-beta.1
```

Then watch the **Android release** run: unit tests, shrunk + signed APK, signature check, release with
`earshot-<version>.apk` + `.sha256`, and `earshot-<version>-mapping` artifact (R8 mapping). Edit the
release on GitHub to paste the notes.

## 5. After publishing

- One-tap update: on a release build with the matching channel, Settings > Check for updates offers
  it; Update downloads, verifies the SHA-256 and installs (first time asks for "Install unknown apps").
  Serena's own phone runs **debug** builds, so it won't see release updates; test with a friend's
  device or the `updatetest` build (see RELEASES.md "One-tap update").
- Watch `crash_reports` over the following days.
- Friend messages: draft only; Serena sends them.

## Pulling a bad release

Releases are never undone. In `app_config` set `hold_version` to the last good version (the update check
then offers nothing newer), fix, and ship a higher version. Use AI / transcribe switches and `banner` to
switch off a broken server feature without a release.
