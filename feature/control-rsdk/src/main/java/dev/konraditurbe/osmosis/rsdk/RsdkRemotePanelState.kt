package dev.konraditurbe.osmosis.rsdk

import dev.konraditurbe.osmosis.modules.CameraRemotePhase
import dev.konraditurbe.osmosis.modules.CameraRemoteState

internal data class RsdkRemotePanelState(
    val connecting: Boolean,
    val connected: Boolean,
    val canConnect: Boolean,
    val canDisconnect: Boolean,
    val canWake: Boolean,
    val commandsEnabled: Boolean,
    val recording: Boolean,
)

internal object RsdkRemotePanelStateMapper {
    fun from(state: CameraRemoteState): RsdkRemotePanelState {
        val connected = state.phase == CameraRemotePhase.CONNECTED
        val disconnected = state.phase == CameraRemotePhase.DISCONNECTED
        return RsdkRemotePanelState(
            connecting = state.phase == CameraRemotePhase.CONNECTING,
            connected = connected,
            canConnect = disconnected,
            canDisconnect = !disconnected,
            canWake = disconnected,
            commandsEnabled = connected,
            recording = state.status?.recording == true,
        )
    }
}
