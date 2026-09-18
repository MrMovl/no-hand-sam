package de.reibisch.hnaudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.reibisch.hnaudio.debug.DebugScreen
import de.reibisch.hnaudio.debug.DebugViewModel
import de.reibisch.hnaudio.settings.SettingsScreen
import de.reibisch.hnaudio.ui.HnAudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HnAudioApp
        setContent {
            HnAudioTheme {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                val vm: DebugViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { DebugViewModel(app.hnClient, app.extractor, app.summaries) }
                    },
                )
                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(app.settings, onDone = {
                        showSettings = false
                        vm.refresh()
                    })
                } else {
                    DebugScreen(vm, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
