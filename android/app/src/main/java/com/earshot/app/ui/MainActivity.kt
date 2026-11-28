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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.collectLatest
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
import com.earshot.app.ui.screens.StatsReviewScreen
import com.earshot.app.ui.screens.StatsScreen
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
            val black by vm.trueBlack.collectAsStateWithLifecycle()
            val textSize by vm.textSize.collectAsStateWithLifecycle()
            EarshotTheme(
                glow = theme == Settings.THEME_GLOW,
                trueBlack = black,
                textScale = if (textSize == Settings.TEXT_COMFORTABLE) Settings.COMFORTABLE_SCALE else 1f,
            ) {
                EarshotRoot(vm)
            }
        }
        handlePlayIntent(intent)
        addResumeShortcut()
    }

    /** Phase 20 (C6): a "Resume" shortcut on the app icon's long-press menu (and for Samsung Modes and Routines). */
    private fun addResumeShortcut() {
        runCatching {
            val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "resume")
                .setShortLabel("Resume")
                .setLongLabel("Resume listening")
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, com.earshot.app.R.drawable.ic_tab_queue))
                .setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_RESUME))
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePlayIntent(intent)
    }

    /** The Play button on a "New episode" notification (phase 10), or a voice request (phase 15). */
    private fun handlePlayIntent(intent: Intent?) {
        // Phase 23: a web article shared to Earshot is read aloud into the queue.
        if (intent?.action == Intent.ACTION_SEND) {
            val url = com.earshot.app.data.articles.Articles.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            intent.action = null // not again on rotation
            if (url == null) {
                android.widget.Toast.makeText(this, "Share a web page link to read it aloud", android.widget.Toast.LENGTH_LONG).show()
            } else {
                com.earshot.app.work.ArticleWorker.enqueue(this, url)
                android.widget.Toast.makeText(this, "Reading the article. It joins your queue when it's ready.", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        if (intent?.action == android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val query = intent.getStringExtra(android.app.SearchManager.QUERY)
            // Bundle.get is deprecated, but the extras' types aren't known here and this only logs them.
            @Suppress("DEPRECATION")
            val extras = intent.extras?.let { b -> b.keySet().joinToString { k -> "$k=${b.get(k)}" } }
            android.util.Log.i("Earshot", "Voice (phone): MEDIA_PLAY_FROM_SEARCH query=\"$query\" extras={$extras}")
            intent.action = null // not again on rotation
            (application as com.earshot.app.EarshotApp).player.playFromSearch(query)
            return
        }
        // Phase 20 (C6): the "Resume" app shortcut (long-press the icon), also usable from Samsung Modes and Routines.
        if (intent?.action == ACTION_RESUME) {
            intent.action = null // not again on rotation
            val player = (application as com.earshot.app.EarshotApp).player
            if (!player.state.value.isPlaying) player.togglePlay()
            return
        }
        if (intent?.action != com.earshot.app.push.Push.ACTION_PLAY) return
        val id = intent.getStringExtra(com.earshot.app.push.Push.EXTRA_EPISODE) ?: return
        intent.action = null // not again on rotation
        androidx.core.app.NotificationManagerCompat.from(this).cancel(id.hashCode())
        vm.playFromNotification(id)
    }

    override fun onResume() {
        super.onResume()
        // Pick up changes made in the web player while the app was in the background.
        (application as com.earshot.app.EarshotApp).sync.request("app resumed")
        // Phase 16: Serena's switches (at most hourly) and any waiting crash reports.
        (application as com.earshot.app.EarshotApp).refreshRemoteConfig()
        // Phase 20 (C5): offer "Continue here" if the web player or another phone is playing.
        vm.checkHandoff()
    }
    companion object {
        /** Phase 20 (C6): opens Earshot and carries on playing. */
        const val ACTION_RESUME = "com.earshot.app.RESUME"
    }
}

@Composable
fun EarshotRoot(vm: AppViewModel) {
    // Phase 16: Serena's minimum version: a warning that can't be dismissed (the app keeps working).
    val config by com.earshot.app.data.RemoteConfig.config.collectAsStateWithLifecycle()
    val minVersion = config.minSupportedVersion
    val updateRequired = config.updateRequired && minVersion != null
    LaunchedEffect(updateRequired) { if (updateRequired) vm.checkForUpdate(manual = false) }
    val snackbar = remember { SnackbarHostState() }
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val current by vm.currentEpisode.collectAsStateWithLifecycle()
    val openPlayingPodcast: () -> Unit = { current?.episode?.podcastId?.let(vm::openPodcastPage) }

    LaunchedEffect(Unit) {
        // Phase 16: collectLatest, so a new message replaces the one showing (quick triage doesn't queue Undos).
        vm.messages.collectLatest { m ->
            // Undo stays up a little longer.
            val r = snackbar.showSnackbar(
                m.text,
                actionLabel = m.actionLabel,
                duration = if (m.actionLabel != null) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short,
            )
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
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
                    if (!vm.showPlayer) com.earshot.app.ui.screens.SkipAdChip(vm)
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
                    if (!vm.showPlayer) com.earshot.app.ui.screens.SkipAdChip(vm)
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
                    Screen.CarSetup -> com.earshot.app.ui.screens.CarSetupScreen(vm)
                    Screen.Stats -> StatsScreen(vm)
                    is Screen.StatsReview -> StatsReviewScreen(vm, screen.kind, screen.from)
                    Screen.BrowseList -> com.earshot.app.ui.screens.BrowseListScreen(vm)
                    Screen.BrowseCategories -> com.earshot.app.ui.screens.BrowseCategoriesScreen(vm)
                    Screen.BrowseSettings -> com.earshot.app.ui.screens.BrowseSettingsScreen(vm)
                }
            }
            ImportBanner(vm)
            com.earshot.app.ui.screens.HandoffBanner(vm) // phase 20 (C5)
            com.earshot.app.ui.screens.UpdateBanner(vm)
            // Phase 16: Serena's warning and note sit at the bottom, clear of the status bar (her test).
            minVersion?.takeIf { updateRequired }?.let { com.earshot.app.ui.screens.UpdateNeededBanner(vm, it) }
            com.earshot.app.ui.screens.ConfigBanner()
        }
    }

    if (vm.showPlayer) {
        PlayerSheet(vm, onDismiss = { vm.showPlayer = false })
    }

    // Phase 13: this version's release notes, once after an update (or from Settings > About).
    vm.whatsNew?.let { notes ->
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::dismissWhatsNew,
            title = { androidx.compose.material3.Text("What's new in Earshot ${notes.version}") },
            text = {
                androidx.compose.material3.Text(
                    com.earshot.app.data.Updates.plainNotes(notes.notes).ifBlank { "No notes for this release." },
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = vm::dismissWhatsNew) { androidx.compose.material3.Text("Close") } },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { runCatching { uri.openUri(notes.pageUrl) }; vm.dismissWhatsNew() }) {
                    androidx.compose.material3.Text("Open on GitHub")
                }
            },
        )
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
