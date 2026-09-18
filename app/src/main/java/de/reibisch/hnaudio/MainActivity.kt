package de.reibisch.hnaudio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.reibisch.hnaudio.debug.DebugScreen
import de.reibisch.hnaudio.debug.DebugViewModel
import de.reibisch.hnaudio.settings.SettingsScreen
import de.reibisch.hnaudio.ui.HnAudioTheme
import de.reibisch.hnaudio.ui.PlayerScreen

private enum class Screen { Player, Debug, Settings }

class MainActivity : ComponentActivity() {
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationPermission()
        val app = application as HnAudioApp
        setContent {
            HnAudioTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.Player) }
                if (screen != Screen.Player) BackHandler { screen = Screen.Player }
                when (screen) {
                    Screen.Player -> PlayerScreen(
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenDebug = { screen = Screen.Debug },
                    )
                    Screen.Settings -> SettingsScreen(app.settings, onDone = { screen = Screen.Player })
                    Screen.Debug -> {
                        val vm: DebugViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    DebugViewModel(app.hnClient, app.extractor, app.summaries, app.speech, app.ttsFiles, app)
                                }
                            },
                        )
                        DebugScreen(vm, onOpenSettings = { screen = Screen.Settings })
                    }
                }
            }
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
