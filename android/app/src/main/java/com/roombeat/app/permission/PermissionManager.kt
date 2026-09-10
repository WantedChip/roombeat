package com.roombeat.app.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Multi-API permission handler and compatibility manager across Android API 30 to 37.
 *
 * Special handling:
 * - API 37: [PERMISSION_ACCESS_LOCAL_NETWORK] required for local network sockets and mDNS.
 * - API 33+: [Manifest.permission.POST_NOTIFICATIONS] required for foreground service notification.
 * - API 33+: [Manifest.permission.READ_MEDIA_AUDIO] replaces broad external storage permissions.
 * - API 30+: [Manifest.permission.RECORD_AUDIO] required for internal audio playback capture.
 */
object PermissionManager {

    /**
     * Android 17 (API 37) forward-compatibility permission for raw local network sockets
     * and mDNS multicast discovery.
     */
    const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /**
     * Android 13+ (API 33) notification permission.
     */
    const val PERMISSION_POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    /**
     * Android 13+ (API 33) audio storage permission.
     */
    const val PERMISSION_READ_MEDIA_AUDIO = Manifest.permission.READ_MEDIA_AUDIO

    /**
     * Core audio recording/capture permission.
     */
    const val PERMISSION_RECORD_AUDIO = Manifest.permission.RECORD_AUDIO

    /**
     * Target API constant for Android 17 Local Network permission enforcement.
     */
    const val API_LOCAL_NETWORK_RESTRICTION = 37

    /**
     * Permission checker delegate allowing unit test isolation across different JDK runtimes.
     */
    internal var permissionChecker: (Context, String) -> Boolean = { context, permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if [permission] is granted in the current [context].
     *
     * For conditional permissions:
     * - [PERMISSION_ACCESS_LOCAL_NETWORK]: on devices below API 37 ([sdkInt] < 37),
     *   local network access is not gated by runtime permission, returning true by default.
     * - [PERMISSION_POST_NOTIFICATIONS]: on devices below API 33 ([sdkInt] < 33),
     *   notifications do not require runtime permission, returning true by default.
     */
    fun hasPermission(
        context: Context,
        permission: String,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        if (permission == PERMISSION_ACCESS_LOCAL_NETWORK && sdkInt < API_LOCAL_NETWORK_RESTRICTION) {
            return true
        }
        if (permission == PERMISSION_POST_NOTIFICATIONS && sdkInt < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return permissionChecker(context, permission)
    }

    /**
     * Checks if notification permission is granted.
     * On API < 33, notifications do not require a runtime permission prompt and return true.
     */
    fun hasNotificationPermission(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean = hasPermission(context, PERMISSION_POST_NOTIFICATIONS, sdkInt)

    /**
     * Checks if local network access permission is granted.
     * On API < 37, local network access is not gated by runtime permission and returns true.
     */
    fun hasLocalNetworkPermission(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean = hasPermission(context, PERMISSION_ACCESS_LOCAL_NETWORK, sdkInt)

    /**
     * Checks if audio storage permission is granted.
     * On API 33+, checks [Manifest.permission.READ_MEDIA_AUDIO].
     * On API 30–32, returns true as SAF is used or broad storage is not enforced here.
     */
    fun hasAudioStoragePermission(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, PERMISSION_READ_MEDIA_AUDIO, sdkInt)
        } else {
            true
        }
    }

    /**
     * Checks if audio capture permission ([Manifest.permission.RECORD_AUDIO]) is granted.
     */
    fun hasAudioCapturePermission(context: Context): Boolean =
        hasPermission(context, PERMISSION_RECORD_AUDIO)

    /**
     * Returns the list of runtime permissions that MUST be requested during onboarding
     * depending on the OS version [sdkInt].
     */
    fun getRequiredOnboardingPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        val permissions = mutableListOf<String>()
        if (sdkInt >= API_LOCAL_NETWORK_RESTRICTION) {
            permissions.add(PERMISSION_ACCESS_LOCAL_NETWORK)
        }
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(PERMISSION_POST_NOTIFICATIONS)
        }
        return permissions
    }

    /**
     * Returns the appropriate audio storage permission for the given API level [sdkInt],
     * or null if no runtime permission is required.
     */
    fun getRequiredAudioStoragePermission(sdkInt: Int = Build.VERSION.SDK_INT): String? {
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            PERMISSION_READ_MEDIA_AUDIO
        } else {
            null
        }
    }

    /**
     * Filters a list of [permissions] down to only those that are currently missing / ungranted.
     */
    fun getMissingPermissions(
        context: Context,
        permissions: List<String>,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): List<String> {
        return permissions.filterNot { hasPermission(context, it, sdkInt) }
    }

    /**
     * Determines whether an educational rationale should be shown to the user
     * for a given permission.
     */
    fun shouldShowRationale(activity: Activity, permission: String): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    /**
     * Provides an explicit user-facing rationale string conforming to RoomBeat's
     * Tactile Acoustic Industrial design principles and interaction flows.
     */
    fun getRationale(permission: String): String {
        return when (permission) {
            PERMISSION_ACCESS_LOCAL_NETWORK ->
                "RoomBeat requires local network access to discover peer devices via mDNS and broadcast multicast audio across your local network."
            PERMISSION_POST_NOTIFICATIONS ->
                "RoomBeat requires notification permissions to maintain continuous background audio capture and display streaming transport controls."
            PERMISSION_READ_MEDIA_AUDIO ->
                "RoomBeat requires audio storage access to browse and stream your local music files."
            PERMISSION_RECORD_AUDIO ->
                "RoomBeat requires audio recording permission to capture internal playback for synchronized streaming."
            else ->
                "RoomBeat requires this permission to operate synchronized audio features."
        }
    }
}
