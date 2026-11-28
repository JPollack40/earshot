# R8 keep rules for release builds (shrinking on since phase 15).
# Media3, Room, WorkManager, Coil, Firebase and AndroidX ship their own consumer rules, so only
# Earshot's own reflection-like uses are listed here. Add a rule when a release-only crash points here.

# Readable stack traces in crash reports (line numbers kept, source file names hidden).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# BuildConfig fields are read at runtime (Supabase URL/key, Firebase values, version).
-keep class com.earshot.app.BuildConfig { *; }
