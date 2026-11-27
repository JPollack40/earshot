package com.earshot.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.ui.screens.AddScreen
import com.earshot.app.ui.screens.BookmarksScreen
import com.earshot.app.ui.screens.TranscriptScreen
import com.earshot.app.ui.screens.TranscriptSearchScreen
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import com.earshot.app.ui.screens.ImportBanner
import com.earshot.app.ui.screens.SettingsScreen
import com.earshot.app.ui.screens.LibraryScreen
import com.earshot.app.ui.screens.MiniPlayer
import com.earshot.app.ui.screens.PlayerSheet
import com.earshot.app.ui.screens.PodcastScreen
import com.earshot.app.ui.screens.PlaylistScreen
import com.earshot.app.data.Settings
import com.earshot.app.ui.screens.GlowMiniPlayer
import com.earshot.app.ui.screens.GlowNavBar
import com.earshot.app.ui.screens.JumpBackChip
import com.earshot.app.ui.theme.EarshotTheme
import com.earshot.app.ui.theme.LocalGlow

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by vm.appTheme.collectAsStateWithLifecycle()
            EarshotTheme(glow = theme == Settings.THEME_GLOW) {
                EarshotRoot(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up changes made in the web player while the app was in the background.
        (application as com.earshot.app.EarshotApp).sync.request("app resumed")
    }
}

@Composable
fun EarshotRoot(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val current by vm.currentEpisode.collectAsStateWithLifecycle()
    val openPlayingPodcast: () -> Unit = { current?.episode?.podcastId?.let(vm::openPodcastPage) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.shares.collect { shareMarkdown(context, it) }
    }
    RequestNotificationPermission()

    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // Screens draw their own top bars; only the bottom (nav bar / mini player) is handled here.
        contentWindowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (LocalGlow.current) {
                // Glow: floating mini player above a bottom bar with the four library sections.
                val prefs by vm.currentPrefs.collectAsStateWithLifecycle()
                val queue by vm.queue.collectAsStateWithLifecycle()
                val inbox by vm.inbox.collectAsStateWithLifecycle()
                Column {
                    if (!vm.showPlayer) JumpBackChip(vm)
                    if (now.episodeId != null) {
                        GlowMiniPlayer(
                            now = now,
                            skipBackMs = prefs.skipBackMs,
                            skipForwardMs = prefs.skipForwardMs,
                            onOpen = { vm.showPlayer = true },
                            onTogglePlay = vm.player::togglePlay,
                            onSkipBack = vm::skipBack,
                            onSkipForward = vm::skipForward,
                            onOpenPodcast = openPlayingPodcast,
                        )
                    }
                    GlowNavBar(
                        selected = if (vm.backStack.last() == Screen.Library) vm.libraryTab else -1,
                        queueCount = queue.size,
                        newCount = inbox.size,
                        onSelect = vm::showTab,
                    )
                }
            } else if (now.episodeId != null) {
                Column {
                    if (!vm.showPlayer) JumpBackChip(vm)
                    MiniPlayer(
                        now = now,
                        onOpen = { vm.showPlayer = true },
                        onTogglePlay = vm.player::togglePlay,
                        onOpenPodcast = openPlayingPodcast,
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val screen = vm.backStack.last()) {
                    Screen.Library -> LibraryScreen(vm)
                    is Screen.PodcastDetail -> PodcastScreen(vm, screen.podcastId)
                    Screen.Add -> AddScreen(vm)
                    Screen.Settings -> SettingsScreen(vm)
                    is Screen.TranscriptView -> TranscriptScreen(vm, screen.episodeId, screen.focusMs)
                    Screen.Bookmarks -> BookmarksScreen(vm)
                    Screen.TranscriptSearch -> TranscriptSearchScreen(vm)
                    is Screen.PlaylistDetail -> PlaylistScreen(vm, screen.playlistId)
                }
            }
            ImportBanner(vm)
        }
    }

    if (vm.showPlayer) {
        PlayerSheet(vm, onDismiss = { vm.showPlayer = false })
    }
}

/**
 * Hands a Markdown export to the share sheet, as a .md file (for Files, Drive, Obsidian and so on)
 * and as text (for apps that take text, like notes and email), so any target can use it.
 */
private fun shareMarkdown(context: Context, share: ShareRequest) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L) it.delete() }
    val file = File(dir, share.fileName)
    file.writeText(share.markdown)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".exports", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, share.fileName.removeSuffix(".md"))
        // Very long text can be too big to pass between apps; the file always carries it all.
        if (share.markdown.length < 100_000) putExtra(Intent.EXTRA_TEXT, share.markdown)
        clipData = android.content.ClipData.newUri(context.contentResolver, share.fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export ${share.fileName}"))
}

/** Android 13+ needs this for the playback and download notifications to show. */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
