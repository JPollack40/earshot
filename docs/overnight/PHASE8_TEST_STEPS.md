# Phase 8 test steps: signed release builds

Branch `claude/project-thread-wseovk` (phase 8 is its first commits). Nothing on your phone or in Supabase changes.
These steps don't install anything on your phone.

## 0. Before you start

- The signing key is in `C:\repos\earshot-keys\` on the VM: `earshot-release.jks`, `earshot-release-passwords.txt`,
  `earshot-release.jks.base64.txt`. **Back up the .jks and the password** now (password manager or a USB stick).
- The four GitHub secrets aren't added yet. Section 2 needs them; section 1 doesn't.

## 1. Test run of the workflow (no secrets needed)

1. Open https://github.com/JPollack40/earshot/actions/workflows/android-release.yml
2. **Run workflow** > branch `claude/project-thread-wseovk` > **Run workflow**.
3. It takes about 10 minutes. Expected: green, with a yellow warning "No signing secrets: building an UNSIGNED test APK".
4. On the run's page, under **Artifacts**, there's `earshot-0.0.0-ci.<number>` (an unsigned APK and its .sha256). It can't be installed (unsigned); this only proves the build works on GitHub.

## 2. Add the secrets, then a signed test run

1. Add the four secrets as in `RELEASES.md` ("One-time: give GitHub the key"). The base64 file goes in with:

```powershell
Get-Content C:\repos\earshot-keys\earshot-release.jks.base64.txt -Raw | Set-Clipboard
```

2. Run the workflow again as in section 1. Expected: green, no "UNSIGNED" warning, and the step **Check signature and name the APK** lists the certificate (its SHA-256 should be `a72e8bf834fa6b9e292da510e73b991aa9491ab6bee627018656ad304b86df97`, the one the VM's signed test build had: CN=Earshot, RSA 4096).
3. Download the artifact. It's a signed APK you could give to a friend (but don't install it on your own phone yet: see the note at the bottom).

## 3. A real release (after merging to main, when you're ready for friends)

```powershell
cd C:\repos\earshot
git checkout main
git pull
git tag v0.9.0
git push origin v0.9.0
```

Expected: the workflow runs by itself, then https://github.com/JPollack40/earshot/releases shows **Earshot 0.9.0** with
`earshot-0.9.0.apk` and `earshot-0.9.0.apk.sha256`. (I suggest 0.9.0 for the first friends release, since phase 9 is
what friends need.)

## 4. Version in the app

On a build from this branch: **Settings > About** shows `Earshot 0.1.0-dev (1, debug)` for your debug builds, and
`Earshot 0.9.0 (900, release)` for the release above.

## Note: your phone and release builds

Your phone has the **debug** build. Android won't install a release build over it (different signing key) without
uninstalling first, which deletes the phone's database. Keep using debug builds for now. When you want to switch, use
phase 9's **Back up everything**, uninstall, install the release APK, then **Restore from a backup**
(steps in `PHASE9_TEST_STEPS.md`, section 5).
