# Shared test vectors

Rules that exist twice, once in Kotlin (the phone) and once in TypeScript (the web player), are
checked against the same cases here, so the two copies can't drift apart (phase 15).

- Phone: `android/app/src/test/java/com/earshot/app/data/SharedVectorsTest.kt` (`gradlew testDebugUnitTest`)
- Web: `web/tests/vectors.test.ts` (`cd web && npm test`)

When a rule changes, change it in both places and add a case here.

| File | Phone | Web |
|---|---|---|
| `feedKey.json` | `Repository.feedKey` | `util.ts feedKey` |
| `keywords.json` | `Automation.normalize` / `words` | `playlists.ts normalize` / `words` |
| `markRange.json` | `MarkRange.pick` | `markRange.ts pickRange` |
| `playlists.json` | `SmartPlaylists.select` | `playlists.ts select` |
