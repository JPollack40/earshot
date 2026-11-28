package com.earshot.app.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import com.earshot.app.data.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Where a one-tap update is (phase 16). */
sealed interface SelfUpdateState {
    data object Idle : SelfUpdateState
    data class Downloading(val percent: Int) : SelfUpdateState
    data object Installing : SelfUpdateState
    /** Android asked the person to allow installs from Earshot first. */
    data object NeedsPermission : SelfUpdateState
    data class Failed(val message: String) : SelfUpdateState
}

/**
 * Phase 16: one-tap update. Downloads the release APK, checks it against the SHA-256 published with the
 * release (and that it is Earshot, newer than this build), then hands it to Android's PackageInstaller.
 * Android shows one confirmation; on Android 14+ Earshot asks to become the app's "update owner", so
 * later updates may need no confirmation at all. Android itself refuses an APK signed with another key.
 */
object SelfUpdate {
    private const val TAG = "Earshot"
    const val ACTION_STATUS = "com.earshot.app.SELF_UPDATE_STATUS"

    private val _state = MutableStateFlow<SelfUpdateState>(SelfUpdateState.Idle)
    val state: StateFlow<SelfUpdateState> = _state.asStateFlow()

    internal fun fail(message: String) {
        Log.w(TAG, "Self-update failed: $message")
        _state.value = SelfUpdateState.Failed(message)
    }

    fun reset() {
        _state.value = SelfUpdateState.Idle
    }

    /** True when Android lets Earshot install apps (Settings > Install unknown apps > Earshot). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" switch for Earshot. */
    fun openInstallPermission(context: Context) {
        val i = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    /** Downloads, checks and installs [info]. Progress and problems are in [state]. */
    suspend fun install(context: Context, info: UpdateInfo) {
        val app = context.applicationContext
        if (_state.value is SelfUpdateState.Downloading || _state.value == SelfUpdateState.Installing) return
        if (!canInstall(app)) {
            _state.value = SelfUpdateState.NeedsPermission
            openInstallPermission(app)
            return
        }
        if (info.apkUrl.isBlank()) return fail("This release has no APK attached")
        if (info.shaUrl.isBlank()) return fail("This release has no SHA-256 file, so it can't be checked. Use Download instead.")
        _state.value = SelfUpdateState.Downloading(0)
        try {
            val file = withContext(Dispatchers.IO) { download(app, info) }
            _state.value = SelfUpdateState.Installing
            withContext(Dispatchers.IO) { commit(app, file) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
    }

    /** Reads "<64 hex>  name" (sha256sum's format) and returns the lower-case hex. */
    internal fun parseSha(text: String): String? =
        Regex("""\b([0-9a-fA-F]{64})\b""").find(text)?.groupValues?.get(1)?.lowercase()

    private suspend fun download(app: Context, info: UpdateInfo): File {
        val expected = parseSha(Http.getText(info.shaUrl)) ?: throw IOException("The release's SHA-256 file couldn't be read")
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only ever one download kept
        val file = File(dir, "earshot-${info.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = Http.open(info.apkUrl)
        try {
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done > 200L * 1024 * 1024) throw IOException("The download is far too big for Earshot")
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = SelfUpdateState.Downloading(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw IOException("The download doesn't match the release's SHA-256, so it wasn't installed. Try again.")
        }
        checkPackage(app, file)
        Log.i(TAG, "Self-update: ${file.name} downloaded and checked (sha256 $actual)")
        return file
    }

    /** The APK must be Earshot itself and newer than this build. */
    private fun checkPackage(app: Context, file: File) {
        val pm = app.packageManager
        val archive: PackageInfo = pm.getPackageArchiveInfo(file.path, 0)
            ?: throw IOException("The download isn't a readable app")
        if (archive.packageName != app.packageName) throw IOException("The download is a different app (${archive.packageName})")
        val mine = pm.getPackageInfo(app.packageName, 0)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(mine)) {
            throw IOException("The download isn't newer than this Earshot")
        }
    }

    private fun commit(app: Context, file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("earshot.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = Intent(app, SelfUpdateReceiver::class.java).setAction(ACTION_STATUS).setPackage(app.packageName)
            // Mutable: the installer adds the status and the confirmation intent to it.
            val pi = PendingIntent.getBroadcast(app, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
        Log.i(TAG, "Self-update: install session $id committed")
    }

    /** What PackageInstaller reported, in plain words. */
    internal fun statusMessage(status: Int, detail: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the update"
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Android refused it: this Earshot was signed differently (a debug or test build can't take a release update)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update doesn't work on this phone"
        PackageInstaller.STATUS_FAILURE_INVALID -> "The download wasn't a valid app"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update"
        else -> "The update didn't install" + (detail?.let { ": $it" } ?: "")
    }
}

/** Receives PackageInstaller's answers for a one-tap update (phase 16). */
class SelfUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SelfUpdate.ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("Earshot", "Self-update: status $status ${detail.orEmpty()}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    SelfUpdate.fail("Android didn't show the install confirmation")
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    SelfUpdate.fail("Couldn't open the install confirmation: ${e.message}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> SelfUpdate.reset() // Android restarts Earshot as the new version
            else -> SelfUpdate.fail(SelfUpdate.statusMessage(status, detail))
        }
    }
}
