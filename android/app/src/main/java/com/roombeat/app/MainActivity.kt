package com.roombeat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.roombeat.app.permission.PermissionManager
import com.roombeat.app.session.PinGenerator
import com.roombeat.app.ui.screens.ActivePlaybackHudScreen
import com.roombeat.app.ui.screens.CalibrationScreen
import com.roombeat.app.ui.screens.ModeSelectScreen
import com.roombeat.app.ui.screens.RoomLobbyScreen
import com.roombeat.app.ui.screens.SourceMethodScreen
import com.roombeat.app.ui.theme.ChassisBase
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.viewmodel.ActivePlaybackHudViewModel
import com.roombeat.app.ui.viewmodel.CalibrationViewModel
import com.roombeat.app.ui.viewmodel.RoomLobbyViewModel
import java.util.UUID

/**
 * Primary Android Launcher Activity for RoomBeat (Phase v1.1 / Sub-phase v1.1.0).
 *
 * Hosts the Jetpack Compose hierarchy under [RoomBeatTheme] and coordinates
 * tactile navigation between the 5 primary system screens:
 * 1. [ModeSelectScreen] (Landing & Mode Selection)
 * 2. [RoomLobbyScreen] (Multi-Device Channel Matrix & PIN/QR Sharing)
 * 3. [SourceMethodScreen] (Ingest Pipeline Selection: Storage, System Capture, Spotify)
 * 4. [CalibrationScreen] (NTP Probe Radar & Sub-Millisecond Acoustic Sync Lock)
 * 5. [ActivePlaybackHudScreen] (60fps VU Peak Meters & Transport Controls)
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions updated in runtime environment
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Progressive onboarding permission verification (API 30–37 compatibility)
        val missingPermissions = PermissionManager.getMissingPermissions(
            this,
            PermissionManager.getRequiredOnboardingPermissions()
        )
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }

        val lobbyViewModel = ViewModelProvider(this)[RoomLobbyViewModel::class.java]
        val calibrationViewModel = ViewModelProvider(this)[CalibrationViewModel::class.java]
        val playbackViewModel = ViewModelProvider(this)[ActivePlaybackHudViewModel::class.java]

        setContent {
            RoomBeatTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ChassisBase)
                        .safeDrawingPadding(),
                    color = ChassisBase
                ) {
                    RoomBeatApp(
                        lobbyViewModel = lobbyViewModel,
                        calibrationViewModel = calibrationViewModel,
                        playbackViewModel = playbackViewModel
                    )
                }
            }
        }
    }
}

/**
 * Top-level application navigation state machine.
 */
sealed interface AppScreen {
    data object ModeSelect : AppScreen
    data class RoomLobby(
        val isHost: Boolean,
        val pin: String,
        val hostAddress: String = "127.0.0.1",
        val port: Int = 8080,
        val sessionId: String = ""
    ) : AppScreen
    data object SourceMethod : AppScreen
    data object Calibration : AppScreen
    data object ActivePlayback : AppScreen
}

@Composable
fun RoomBeatApp(
    lobbyViewModel: RoomLobbyViewModel,
    calibrationViewModel: CalibrationViewModel,
    playbackViewModel: ActivePlaybackHudViewModel
) {
    var backstack by remember { mutableStateOf(listOf<AppScreen>(AppScreen.ModeSelect)) }
    val currentScreen = backstack.lastOrNull() ?: AppScreen.ModeSelect

    // Hardware/gesture back press handler
    BackHandler(enabled = backstack.size > 1) {
        backstack = backstack.dropLast(1)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentScreen) {
            is AppScreen.ModeSelect -> {
                ModeSelectScreen(
                    onCreateRoom = {
                        val pin = PinGenerator.generate()
                        val sessionId = UUID.randomUUID().toString().take(8)
                        lobbyViewModel.initAsHost(
                            pin = pin,
                            ip = "127.0.0.1",
                            port = 8080,
                            sessionId = sessionId
                        )
                        backstack = backstack + AppScreen.RoomLobby(
                            isHost = true,
                            pin = pin,
                            hostAddress = "127.0.0.1",
                            port = 8080,
                            sessionId = sessionId
                        )
                    },
                    onJoinRoom = {
                        val sessionId = UUID.randomUUID().toString().take(8)
                        lobbyViewModel.initAsClient(
                            hostIp = "127.0.0.1",
                            port = 8080,
                            pin = "",
                            sessionId = sessionId,
                            hostName = "Host Rig"
                        )
                        backstack = backstack + AppScreen.RoomLobby(
                            isHost = false,
                            pin = "",
                            hostAddress = "127.0.0.1",
                            port = 8080,
                            sessionId = sessionId
                        )
                    }
                )
            }

            is AppScreen.RoomLobby -> {
                RoomLobbyScreen(
                    viewModel = lobbyViewModel,
                    onProceedToSource = {
                        backstack = backstack + AppScreen.SourceMethod
                    },
                    onLeaveRoom = {
                        backstack = listOf(AppScreen.ModeSelect)
                    }
                )
            }

            is AppScreen.SourceMethod -> {
                SourceMethodScreen(
                    onNavigateBack = {
                        if (backstack.size > 1) {
                            backstack = backstack.dropLast(1)
                        }
                    },
                    onProceedToPlayback = { _ ->
                        backstack = backstack + AppScreen.Calibration
                    },
                    onProceedToSpotify = {
                        backstack = backstack + AppScreen.Calibration
                    }
                )
            }

            is AppScreen.Calibration -> {
                CalibrationScreen(
                    viewModel = calibrationViewModel,
                    onProceedToAudioSource = {
                        backstack = backstack + AppScreen.ActivePlayback
                    },
                    onNavigateBack = {
                        if (backstack.size > 1) {
                            backstack = backstack.dropLast(1)
                        }
                    }
                )
            }

            is AppScreen.ActivePlayback -> {
                ActivePlaybackHudScreen(
                    viewModel = playbackViewModel,
                    onSessionEnded = {
                        backstack = listOf(AppScreen.ModeSelect)
                    }
                )
            }
        }
    }
}
