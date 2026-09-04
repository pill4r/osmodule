package dev.konraditurbe.osmosis.pocket4p

data class Pocket4pPluginRuntimeState(val active: Boolean, val cameraName: String?)

/** Minimal process state exposed to Base through the signed plugin Binder. */
object Pocket4pPluginRuntime {
    private data class OwnedState(
        val owner: Any?,
        val public: Pocket4pPluginRuntimeState,
    )

    @Volatile private var state = OwnedState(null, Pocket4pPluginRuntimeState(false, null))

    fun snapshot(): Pocket4pPluginRuntimeState = state.public

    @Synchronized
    internal fun connected(owner: Any, cameraName: String) {
        state = OwnedState(owner, Pocket4pPluginRuntimeState(true, cameraName))
    }

    @Synchronized
    internal fun disconnected(owner: Any) {
        if (state.owner === owner) {
            state = OwnedState(null, Pocket4pPluginRuntimeState(false, null))
        }
    }
}
