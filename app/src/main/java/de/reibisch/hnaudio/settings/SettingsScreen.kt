package de.reibisch.hnaudio.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.reibisch.hnaudio.summary.GeminiSummarizer
import kotlinx.coroutines.launch
import de.reibisch.hnaudio.voice.VoiceModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: Settings, voiceModel: VoiceModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val hasKey by settings.hasApiKey.collectAsState(initial = false)
    val savedModel by settings.model.collectAsState(initial = GeminiSummarizer.DEFAULT_MODEL)
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    LaunchedEffect(savedModel) { model = savedModel }
    val savedRate by settings.speechRate.collectAsState(initial = 1.0f)
    var rate by remember { mutableStateOf(1.0f) }
    LaunchedEffect(savedRate) { rate = savedRate }
    val savedCount by settings.storyCount.collectAsState(initial = Settings.DEFAULT_STORY_COUNT)
    var count by remember { mutableStateOf(Settings.DEFAULT_STORY_COUNT) }
    LaunchedEffect(savedCount) { count = savedCount }
    val savedAskShow by settings.includeAskShow.collectAsState(initial = true)
    var askShow by remember { mutableStateOf(true) }
    LaunchedEffect(savedAskShow) { askShow = savedAskShow }
    val heardCount by settings.heardCount.collectAsState(initial = 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Gemini API key", style = MaterialTheme.typography.titleMedium)
            Text(
                if (hasKey) "A key is saved. Enter a new one to replace it." else "No key saved yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                supportingText = { Text("Default: ${GeminiSummarizer.DEFAULT_MODEL}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Speech rate: %.2f×".format(rate), style = MaterialTheme.typography.titleMedium)
            Slider(
                value = rate,
                onValueChange = { rate = (it * 20).toInt() / 20f },
                valueRange = 0.5f..2.0f,
            )
            Text("Stories per session: $count", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = count.toFloat(),
                onValueChange = { count = (it / 5).toInt() * 5 },
                valueRange = 5f..60f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Include Ask / Show / Tell HN", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = askShow, onCheckedChange = { askShow = it })
            }
            Text(
                "Stories you've heard are skipped next time ($heardCount remembered).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { scope.launch { settings.clearHeard() } }, enabled = heardCount > 0) {
                Text("Forget heard stories")
            }
            VoiceModelSection(voiceModel)
            Button(
                onClick = {
                    scope.launch {
                        if (key.isNotBlank()) settings.setApiKey(key)
                        settings.setModel(model)
                        settings.setSpeechRate(rate)
                        settings.setStoryCount(count)
                        settings.setIncludeAskShow(askShow)
                        key = ""
                        onDone()
                    }
                },
            ) { Text("Save") }
        }
    }
}

@Composable
private fun VoiceModelSection(model: VoiceModel) {
    val scope = rememberCoroutineScope()
    val installed by model.installed.collectAsState()
    val progress by model.progress.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    Text("Voice commands", style = MaterialTheme.typography.titleMedium)
    Text(
        if (installed) {
            "Offline English speech model installed."
        } else {
            "Needs a one-time download of an offline English speech model (${VoiceModel.SIZE_LABEL}). " +
                "Nothing you say leaves the phone."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    when {
        progress != null -> Unit
        installed -> OutlinedButton(onClick = { model.delete() }) { Text("Delete speech model") }
        else -> OutlinedButton(onClick = {
            error = null
            scope.launch {
                try {
                    model.download()
                } catch (e: java.io.IOException) {
                    error = "Download failed: ${e.message}"
                }
            }
        }) { Text("Download speech model") }
    }
}
