package dev.konraditurbe.osmosis.plugin

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * Synchronous first-launch trampoline shared by every plugin APK.
 *
 * Plugin manifests export it under [PluginContract.BIND_PERMISSION]. Base calls it only after signer
 * and protocol verification. Acquiring this provider explicitly starts a stopped/not-launched plugin
 * without crossing OEM background-Activity policy; returning from [call] also removes any process
 * startup race before Base binds the plugin service.
 */
class PluginBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceHostCaller()
        require(method == PluginContract.BOOTSTRAP_METHOD) { "Unsupported bootstrap method: $method" }
        return Bundle().apply {
            putInt(PluginContract.KEY_BOOTSTRAP_PROTOCOL, PluginContract.PROTOCOL_VERSION)
        }
    }

    private fun enforceHostCaller() {
        val packages = context?.packageManager?.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (PluginContract.HOST_PACKAGE !in packages) {
            throw SecurityException("Only osmodule Base may bootstrap this plugin")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
