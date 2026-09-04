package dev.konraditurbe.osmosis.modules

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.util.Locale

/**
 * Base-process authority for the one camera transport shared by Base and same-signature plugins.
 *
 * Every mutation is delegated to [arbiter], whose acquire operation atomically checks and installs
 * an owner. The owner Binder is death-linked, so a crashed plugin cannot leave a stale lease.
 */
class CameraSessionOwnerProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        context?.enforceCallingOrSelfPermission(
            CameraSessionOwnerContract.PERMISSION,
            "Camera-session ownership is restricted to same-signature components",
        ) ?: return errorResponse("Camera-session provider has no context")

        return when (method) {
            CameraSessionOwnerContract.METHOD_ACQUIRE -> acquire(extras)
            CameraSessionOwnerContract.METHOD_RELEASE -> release(extras)
            else -> errorResponse("Unsupported camera-session operation")
        }
    }

    private fun acquire(extras: Bundle?): Bundle {
        val ownerId = extras?.getString(CameraSessionOwnerContract.KEY_OWNER_ID)?.trim().orEmpty()
        val address = extras?.getString(CameraSessionOwnerContract.KEY_CAMERA_ADDRESS)?.trim().orEmpty()
        val purpose = extras?.getString(CameraSessionOwnerContract.KEY_PURPOSE)?.trim().orEmpty()
        val binder = extras?.getBinder(CameraSessionOwnerContract.KEY_OWNER_TOKEN)
        if (ownerId.isBlank() || address.isBlank() || purpose.isBlank() || binder == null) {
            return errorResponse("Incomplete camera ownership request")
        }
        if (ownerId.length > MAX_VALUE_LENGTH || address.length > MAX_VALUE_LENGTH ||
            purpose.length > MAX_VALUE_LENGTH
        ) {
            return errorResponse("Camera ownership request is too long")
        }

        val request = CameraSessionOwnerRequest(
            ownerId = ownerId,
            cameraAddress = address.uppercase(Locale.ROOT),
            purpose = purpose,
        )
        return when (val result = arbiter.acquire(request, BinderCameraSessionDeathToken(binder))) {
            is CameraSessionArbiterResult.Granted -> Bundle().apply {
                putString(CameraSessionOwnerContract.KEY_RESULT, CameraSessionOwnerContract.RESULT_GRANTED)
                putLong(CameraSessionOwnerContract.KEY_LEASE_ID, result.leaseId)
            }

            is CameraSessionArbiterResult.Busy -> Bundle().apply {
                putString(CameraSessionOwnerContract.KEY_RESULT, CameraSessionOwnerContract.RESULT_BUSY)
                putString(CameraSessionOwnerContract.KEY_OWNER_ID, result.active.ownerId)
                putString(CameraSessionOwnerContract.KEY_CAMERA_ADDRESS, result.active.cameraAddress)
                putString(CameraSessionOwnerContract.KEY_PURPOSE, result.active.purpose)
            }

            CameraSessionArbiterResult.Rejected ->
                errorResponse("Camera ownership token is not alive")
        }
    }

    private fun release(extras: Bundle?): Bundle {
        val leaseId = extras?.getLong(CameraSessionOwnerContract.KEY_LEASE_ID, 0L) ?: 0L
        val binder = extras?.getBinder(CameraSessionOwnerContract.KEY_OWNER_TOKEN)
        val released = leaseId > 0L && binder != null && arbiter.release(
            leaseId,
            BinderCameraSessionDeathToken(binder),
        )
        return Bundle().apply {
            putString(CameraSessionOwnerContract.KEY_RESULT, CameraSessionOwnerContract.RESULT_GRANTED)
            putBoolean(CameraSessionOwnerContract.KEY_RELEASED, released)
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

    private fun errorResponse(reason: String) = Bundle().apply {
        putString(CameraSessionOwnerContract.KEY_RESULT, CameraSessionOwnerContract.RESULT_ERROR)
        putString(CameraSessionOwnerContract.KEY_ERROR, reason)
    }

    private companion object {
        const val MAX_VALUE_LENGTH = 256
        val arbiter = CameraSessionArbiter()
    }
}
