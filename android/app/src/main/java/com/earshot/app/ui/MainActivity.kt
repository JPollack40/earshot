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
import com.earshot.app.ui.screens.ImportBanner
import com.earshot.app.ui.screens.SettingsScreen
import com.earshot.app.ui.screens.LibraryScreen
import com.earshot.app.ui.screens.MiniPlayer
import com.earshot.app.ui.screens.PlayerSheet
import com.earshot.app.ui.screens.PodcastScreen
import com.earshot.app.ui.theme.EarshotTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EarshotTheme {
                EarshotRoot(vm)
            }
        }
    }
}

@Composable
fun EarshotRoot(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val now by vm.nowPlaying.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    RequestNotificationPermission()

    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // Screens draw their own top bars; only the bottom (nav bar / mini player) is handled here.
        contentWindowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (now.episodeId != null) {
                MiniPlayer(
                    now = now,
                    onOpen = { vm.showPlayer = true },
                    onTogglePlay = vm.player::togglePlay,
                )
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
                }
            }
            ImportBanner(vm)
        }
    }

    if (vm.showPlayer) {
        PlayerSheet(vm, onDismiss = { vm.showPlayer = false })
    }
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
