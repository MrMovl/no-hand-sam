package de.reibisch.hnaudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.reibisch.hnaudio.debug.DebugScreen
import de.reibisch.hnaudio.debug.DebugViewModel
import de.reibisch.hnaudio.ui.HnAudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HnAudioApp
        setContent {
            HnAudioTheme {
                val vm: DebugViewModel = viewModel(
                    factory = viewModelFactory { initializer { DebugViewModel(app.hnClient, app.extractor) } },
                )
                DebugScreen(vm)
            }
        }
    }
}
