package com.earshot.app.data

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import com.earshot.app.BuildConfig
import com.earshot.app.EarshotApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup and restore (phase 9): everything on this phone in one .zip file, so the library can
 * move between installs that can't update each other (the debug build and the release build are
 * signed with different keys), or to a new phone without relying on sync.
 *
 * The file holds the whole database (podcasts, pins, episodes and their state, queue, transcripts,
 * bookmarks, playlists, listening history, AI results), this phone's settings, and a manifest.
 * Downloads aren't included (they download again), nor the sign-in (sign in again after restoring).
 */
object Backup {
    private const val TAG = "Backup"
    private const val DB_NAME = "earshot.db"
    private const val DB_ENTRY = "earshot.db"
    private const val SETTINGS_ENTRY = "settings.xml"
    private const val MANIFEST_ENTRY = "manifest.json"

    /** Newest database version this build understands (AppDatabase's version). */
    private const val SCHEMA = 9

    class BackupException(message: String) : Exception(message)

    data class Info(val createdAt: Long, val appVersion: String, val schema: Int, val podcasts: Int, val episodes: Int)

    fun fileName(now: Long = System.currentTimeMillis()): String =
        "earshot-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now)) + ".zip"

    /** Writes the backup to [uri] (from the system's "save as" picker). */
    suspend fun export(app: EarshotApp, uri: Uri): Info = withContext(Dispatchers.IO) {
        // Fold the write-ahead log into the main file so one file holds everything.
        app.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val dbFile = app.getDatabasePath(DB_NAME)
        val settingsFile = File(File(app.applicationInfo.dataDir, "shared_prefs"), "settings.xml")
        val podcasts = count(app, "podcasts")
        val episodes = count(app, "episodes")
        val now = System.currentTimeMillis()
        val manifest = JSONObject()
            .put("app", "earshot")
            .put("createdAt", now)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("schema", SCHEMA)
            .put("podcasts", podcasts)
            .put("episodes", episodes)
        val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("Couldn't open the file to write")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifest.toString(2).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            dbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            if (settingsFile.exists()) {
                zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
                settingsFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        Log.i(TAG, "Backed up $podcasts podcasts, $episodes episodes")
        Info(now, BuildConfig.VERSION_NAME, SCHEMA, podcasts, episodes)
    }

    /** Reads a backup into a staging folder and checks it. Nothing on the phone changes yet. */
    suspend fun inspect(app: EarshotApp, uri: Uri): Info = withContext(Dispatchers.IO) {
        val dir = stagingDir(app)
        dir.deleteRecursively()
        dir.mkdirs()
        var manifest: JSONObject? = null
        val input = app.contentResolver.openInputStream(uri) ?: throw BackupException("Couldn't open that file")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    MANIFEST_ENTRY -> manifest = JSONObject(zip.readBytes().decodeToString())
                    DB_ENTRY, SETTINGS_ENTRY -> File(dir, entry.name).outputStream().use { zip.copyTo(it) }
                }
            }
        }
        val m = manifest ?: throw BackupException("That isn't an Earshot backup")
        if (m.optString("app") != "earshot" || !File(dir, DB_ENTRY).exists()) throw BackupException("That isn't an Earshot backup")
        // The database's own version is what counts (Room keeps it in user_version).
        val schema = SQLiteDatabase.openDatabase(File(dir, DB_ENTRY).path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        if (schema > SCHEMA) {
            throw BackupException("This backup is from a newer Earshot (${m.optString("appVersion")}). Update the app first, then restore.")
        }
        if (schema < 1) throw BackupException("The backup's database is empty or damaged")
        Info(m.optLong("createdAt"), m.optString("appVersion"), schema, m.optInt("podcasts"), m.optInt("episodes"))
    }

    /**
     * Replaces this phone's database and settings with the backup checked by [inspect], then
     * restarts the app. An older backup is upgraded on the next start (the usual migrations).
     * Sync starts over afterwards: the next sync pulls everything and newer changes win.
     */
    fun restoreAndRestart(app: EarshotApp) {
        val dir = stagingDir(app)
        val staged = File(dir, DB_ENTRY)
        if (!staged.exists()) throw BackupException("Choose the backup again")
        app.db.close()
        val dbFile = app.getDatabasePath(DB_NAME)
        listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"), File(dbFile.path + "-journal")).forEach { it.delete() }
        staged.copyTo(dbFile, overwrite = true)
        // Downloads whose files aren't on this phone are shown as not downloaded (they download again).
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val gone = mutableListOf<String>()
            db.rawQuery("SELECT id, localPath FROM episodes WHERE downloadState != 0 OR localPath IS NOT NULL", null).use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(1)
                    if (path == null || !File(path).exists()) gone += c.getString(0)
                }
            }
            for (id in gone) {
                db.execSQL("UPDATE episodes SET downloadState = 0, downloadProgress = 0, localPath = NULL, downloadedAt = 0 WHERE id = ?", arrayOf(id))
            }
        }
        val prefsDir = File(app.applicationInfo.dataDir, "shared_prefs")
        File(dir, SETTINGS_ENTRY).takeIf { it.exists() }?.copyTo(File(prefsDir, "settings.xml"), overwrite = true)
        // Pull everything again on the next sync (changed_at decides which side's change wins).
        app.getSharedPreferences("sync_state", Context.MODE_PRIVATE).edit()
            .remove("rev_podcasts").remove("rev_episodes").remove("rev_settings").remove("rev_transcripts")
            .remove("rev_bookmarks").remove("rev_playlists").remove("rev_episode_ai")
            .commit()
        dir.deleteRecursively()
        Log.i(TAG, "Restored a backup; restarting")
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launch?.component != null) {
            app.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Runtime.getRuntime().exit(0)
    }

    private fun stagingDir(context: Context) = File(context.cacheDir, "restore")

    private fun count(app: EarshotApp, table: String): Int =
        app.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
