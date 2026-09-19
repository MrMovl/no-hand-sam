package de.reibisch.hnaudio.voice

import kotlinx.coroutines.flow.MutableStateFlow

sealed interface VoiceStatus {
    data object Off : VoiceStatus
    data object Starting : VoiceStatus
    data object Listening : VoiceStatus
    data class Failed(val message: String) : VoiceStatus
}

/** Shared between the listening service and the UI (same process). */
object VoiceState {
    val status = MutableStateFlow<VoiceStatus>(VoiceStatus.Off)
    val lastHeard = MutableStateFlow<String?>(null)
}
