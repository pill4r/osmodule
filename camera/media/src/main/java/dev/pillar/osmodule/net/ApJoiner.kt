package dev.pillar.osmodule.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier

/**
 * Joins the camera's WiFi AP (WPA2-PSK, internet-less) via WifiNetworkSpecifier (API 29+) and
 * binds the process to it so our HTTP/UDP sockets egress over the camera network. This is the
 * Android-native replacement for osmo-download's macOS `networksetup` juggling.
 */
class ApJoiner(context: Context, private val listener: Listener) {
    interface Listener {
        fun onLog(s: String)
        fun onNetwork(network: Network, link: LinkProperties?)
        fun onFailed(reason: String)
        /**
         * The AP went away after a successful join. Retry policy lives with the caller, which is the
         * only thing that knows whether a session is still worth saving — call [rejoin] to try again.
         */
        fun onLost()
    }

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val lifecycleLock = Any()
    private var cb: ConnectivityManager.NetworkCallback? = null
    private var generation = 0
    private var active = false

    // Remembered so [rejoin] can re-issue the identical request without the caller having to hold
    // the credentials for the whole session.
    private var lastSsid: String? = null
    private var lastPassphrase: String = ""
    private var lastWpa3: Boolean = false

    fun join(ssid: String, passphrase: String, wpa3: Boolean = false) {
        val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (passphrase.isNotEmpty()) {
            // WPA2 is the verified default; retain WPA3 for models/firmware that declare it.
            if (wpa3) specBuilder.setWpa3Passphrase(passphrase) else specBuilder.setWpa2Passphrase(passphrase)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        synchronized(lifecycleLock) {
            lastSsid = ssid
            lastPassphrase = passphrase
            lastWpa3 = wpa3
            active = true
            requestLocked(request, ssid, wpa3)
        }
    }

    /**
     * Re-issue the last [join] after the AP dropped.
     *
     * A `WifiNetworkSpecifier` request does not reconnect on its own — once the camera's AP goes away
     * the process stays bound to a dead network and every socket fails `ENONET`, which is exactly how
     * an interrupted transfer surfaced (a Pocket 4 Pro lost its AP 49 s into a download and nothing
     * downstream could recover). Unregister and request again; `onAvailable` re-binds the process.
     *
     * @return false if there is nothing to retry because [join] was never called.
     */
    fun rejoin(): Boolean {
        synchronized(lifecycleLock) {
            if (!active) return false
            val ssid = lastSsid ?: return false
            val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (lastPassphrase.isNotEmpty()) {
                if (lastWpa3) specBuilder.setWpa3Passphrase(lastPassphrase)
                else specBuilder.setWpa2Passphrase(lastPassphrase)
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specBuilder.build())
                .build()
            requestLocked(request, ssid, lastWpa3)
            return true
        }
    }

    fun release() {
        synchronized(lifecycleLock) {
            active = false
            generation++
            cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            cb = null
            unbindIfOwnerLocked()
        }
    }

    /**
     * Must run under [lifecycleLock]. Registering and releasing are deliberately serialized: an old
     * `onAvailable` must never resume after [release] and re-bind the process to a dead camera AP.
     */
    private fun requestLocked(request: NetworkRequest, ssid: String, wpa3: Boolean) {
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cb = null
        unbindIfOwnerLocked()
        val callbackGeneration = ++generation
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lifecycleLock) {
                    if (!isCurrentLocked(this, callbackGeneration)) return
                    val bound = bindAsOwnerLocked(network)
                    val link = runCatching { cm.getLinkProperties(network) }.getOrNull()
                    listener.onLog("WiFi: onAvailable, bindProcessToNetwork=$bound")
                    // Keep delivery inside the lifecycle critical section. release() will therefore
                    // either win before the bind or wait and unbind immediately after this callback.
                    if (isCurrentLocked(this, callbackGeneration)) listener.onNetwork(network, link)
                }
            }

            override fun onUnavailable() {
                synchronized(lifecycleLock) {
                    if (!isCurrentLocked(this, callbackGeneration)) return
                    active = false
                    cb = null
                    generation++
                    unbindIfOwnerLocked()
                    listener.onFailed("WiFi: onUnavailable (wrong password, AP down, or user cancelled)")
                }
            }

            override fun onLost(network: Network) {
                synchronized(lifecycleLock) {
                    if (!isCurrentLocked(this, callbackGeneration)) return
                    unbindIfOwnerLocked()
                    listener.onLog("WiFi: onLost")
                    listener.onLost()
                }
            }
        }
        cb = callback
        listener.onLog("WiFi: requesting \"$ssid\" (${if (wpa3) "WPA3" else "WPA2"}, no-internet)...")
        try {
            cm.requestNetwork(request, callback)
        } catch (error: Throwable) {
            if (cb === callback) {
                cb = null
                active = false
                generation++
            }
            throw error
        }
    }

    private fun isCurrentLocked(
        callback: ConnectivityManager.NetworkCallback,
        callbackGeneration: Int,
    ): Boolean = active && cb === callback && generation == callbackGeneration

    private fun bindAsOwnerLocked(network: Network): Boolean = synchronized(processBindingLock) {
        cm.bindProcessToNetwork(network).also { bound ->
            if (bound) processBindingOwner = this
        }
    }

    private fun unbindIfOwnerLocked() {
        synchronized(processBindingLock) {
            if (processBindingOwner !== this) return
            runCatching { cm.bindProcessToNetwork(null) }
            processBindingOwner = null
        }
    }

    private companion object {
        /** Guards the process-global route across multiple short-lived [ApJoiner] instances. */
        val processBindingLock = Any()
        var processBindingOwner: ApJoiner? = null
    }
}
