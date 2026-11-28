# Release builds (phase 8)

Release APKs are built and signed by GitHub Actions (`.github/workflows/android-release.yml`).
Debug builds from Android Studio / `gradlew.bat assembleDebug` work exactly as before.

## The signing key

- Lives on the VM in `C:\repos\earshot-keys\` (outside every git folder):
  - `earshot-release.jks`: the key store (PKCS12, RSA 4096, alias `earshot`, valid about 27 years)
  - `earshot-release-passwords.txt`: its password (the store and key passwords are the same; PKCS12 requires it)
  - `earshot-release.jks.base64.txt`: the same key store as one line of text, for the GitHub secret
- **Back it up** somewhere outside the VM (a password manager is ideal: attach the .jks and paste the password).
  If the key is lost, phones with a release build installed can't take an update: they would have to uninstall
  (losing anything not synced) and install a build signed with a new key.
- Never commit it. `*.jks`, `*.keystore` and `keystore.properties` are gitignored.

## One-time: give GitHub the key (4 secrets)

github.com/JPollack40/earshot > **Settings > Secrets and variables > Actions > New repository secret**, four times:

| Name | Value |
|---|---|
| `EARSHOT_KEYSTORE_BASE64` | the whole contents of `earshot-release.jks.base64.txt` |
| `EARSHOT_KEYSTORE_PASSWORD` | the password from `earshot-release-passwords.txt` |
| `EARSHOT_KEY_ALIAS` | `earshot` |
| `EARSHOT_KEY_PASSWORD` | the same password again |

To copy a file's contents on the VM without opening an editor (PowerShell):

```
Get-Content C:\repos\earshot-keys\earshot-release.jks.base64.txt -Raw | Set-Clipboard
```

Secrets are encrypted and never shown in logs, even though the repo is public.

## Making a release

Versions come from the tag: `v0.8.0` becomes version name `0.8.0` and version code `800`
(major × 10000 + minor × 100 + patch). Each release needs a higher number than the last.

```
cd C:\repos\earshot
git checkout main
git pull
git tag v0.8.0
git push origin v0.8.0
```

GitHub Actions then runs the unit tests, builds and signs the APK, checks the signature, and publishes a
Release at github.com/JPollack40/earshot/releases with `earshot-0.8.0.apk` and its SHA-256.

**Test run without a release:** Actions tab > **Android release** > **Run workflow** (on any branch). The APK is
kept as a downloadable artifact on that run's page for 90 days. Without the secrets it is unsigned
(can't be installed) but still proves the build works.

## Building a signed release on the VM (optional)

Create `android\keystore.properties` (gitignored) with:

```
storeFile=C:/repos/earshot-keys/earshot-release.jks
storePassword=<password>
keyAlias=earshot
keyPassword=<password>
```

then `gradlew.bat assembleRelease`. The APK is `android\app\build\outputs\apk\release\app-release.apk`.

## Debug and release builds can't update each other

Android only installs an update signed with the same key as the installed app. Serena's phone has the
**debug** build (signed with the VM's debug key), so a release APK can't be installed over it without
uninstalling first, which deletes the phone's library database and downloads. Sync brings back most
things (subscriptions, pins, queue, positions, transcripts, bookmarks, playlists), but not downloads or
phone-only settings. Plan: keep Serena's phone on debug builds for now, and use release builds for friends. To switch
later: Settings > Backup > Back up everything, uninstall, install the release APK, then Settings > Backup >
Restore from a backup (phase 9).

## Shrinking

Since phase 15, release builds are shrunk by R8 (`isMinifyEnabled` and `isShrinkResources`), with
keep rules in `android/app/proguard-rules.pro`. To test a shrunk build without touching the installed
app, build `gradlew assembleR8test`: the same release build, signed with the debug key, installed
beside the normal app as **Earshot R8 test** (`com.jpollack.earshot.r8test`). Sign in there, let sync
bring the library, test, then uninstall it.

Each release run also keeps R8's `mapping.txt` as a workflow artifact (`earshot-<version>-mapping`,
not on the release page), which turns a shrunk crash report back into readable class names.

## Server backup (phase 15)

`.github/workflows/server-backup.yml` dumps the Supabase database every Monday (and on demand:
Actions > Server backup > Run workflow), encrypts it with your passphrase and keeps it for 90 days as
a workflow artifact (Actions > the run > Artifacts). The repository is public, so the file is only
useful with the passphrase.

One-time setup, three repository secrets (Settings > Secrets and variables > Actions > New repository secret):

- `SUPABASE_DB_URL`: Supabase > your project > **Connect** > **Session pooler** connection string
  **without the password**: delete `:[YOUR-PASSWORD]`, leaving
  `postgresql://postgres.<project ref>@<host>:5432/postgres`.
- `SUPABASE_DB_PASSWORD`: the database password (Project Settings > Database > Reset database
  password if you don't have it; the apps don't use it, so resetting is harmless). It's kept out of
  the URL so special characters need no escaping and an error can't print it into the public log.
- `BACKUP_PASSPHRASE`: a long passphrase that only you keep, in your password manager.

To open a backup (on the VM, Git Bash has gpg): download the artifact zip, unzip it, then

```
gpg -o earshot-db.sql.gz -d earshot-db-<date>.sql.gz.gpg
```

It holds Earshot's tables (schema and data) and the user accounts. To restore into a new Supabase
project: create the users first (or restore `auth.users` from the dump's second part), then run
the dump with `psql "<new project's connection string>" -f earshot-db.sql` and deploy the edge
functions and secrets again.
