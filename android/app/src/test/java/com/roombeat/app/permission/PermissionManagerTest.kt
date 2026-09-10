package com.roombeat.app.permission

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PermissionManagerTest {

    private class FakeContext : ContextWrapper(null)

    private val originalChecker = PermissionManager.permissionChecker
    private var grantedSet = mutableSetOf<String>()

    @Before
    fun setUp() {
        grantedSet.clear()
        PermissionManager.permissionChecker = { _, permission ->
            permission in grantedSet
        }
    }

    @After
    fun tearDown() {
        PermissionManager.permissionChecker = originalChecker
    }

    @Test
    fun testAccessLocalNetworkCompatibility_Api37RequiresRuntimePermission() {
        val fakeContext = FakeContext()

        // Denied case
        assertFalse(
            "On API 37, ACCESS_LOCAL_NETWORK must require runtime check",
            PermissionManager.hasLocalNetworkPermission(fakeContext, sdkInt = 37)
        )

        // Granted case
        grantedSet.add(PermissionManager.PERMISSION_ACCESS_LOCAL_NETWORK)
        assertTrue(
            "On API 37, when granted it should return true",
            PermissionManager.hasLocalNetworkPermission(fakeContext, sdkInt = 37)
        )
    }

    @Test
    fun testAccessLocalNetworkCompatibility_Api36AndBelowAutomaticallyPermitted() {
        val fakeContext = FakeContext()

        assertTrue(
            "API 36 should not gate local network access",
            PermissionManager.hasLocalNetworkPermission(fakeContext, sdkInt = 36)
        )
        assertTrue(
            "API 34 should not gate local network access",
            PermissionManager.hasLocalNetworkPermission(fakeContext, sdkInt = 34)
        )
        assertTrue(
            "API 30 should not gate local network access",
            PermissionManager.hasLocalNetworkPermission(fakeContext, sdkInt = 30)
        )
    }

    @Test
    fun testPostNotificationsCompatibility_Api33RequiresRuntimePermission() {
        val fakeContext = FakeContext()

        assertFalse(
            "On API 33+, notification permission must be checked at runtime",
            PermissionManager.hasNotificationPermission(fakeContext, sdkInt = 33)
        )

        grantedSet.add(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(
            "On API 33+, when notification permission is granted, returns true",
            PermissionManager.hasNotificationPermission(fakeContext, sdkInt = 33)
        )
    }

    @Test
    fun testPostNotificationsCompatibility_Api32AndBelowAutomaticallyPermitted() {
        val fakeContext = FakeContext()

        assertTrue(
            "API 32 should automatically permit notifications",
            PermissionManager.hasNotificationPermission(fakeContext, sdkInt = 32)
        )
        assertTrue(
            "API 30 should automatically permit notifications",
            PermissionManager.hasNotificationPermission(fakeContext, sdkInt = 30)
        )
    }

    @Test
    fun testReadMediaAudioCompatibility() {
        val fakeContext = FakeContext()

        // On API 33+, READ_MEDIA_AUDIO is required
        val requiredPerm33 = PermissionManager.getRequiredAudioStoragePermission(sdkInt = 33)
        assertEquals(Manifest.permission.READ_MEDIA_AUDIO, requiredPerm33)

        assertFalse(
            "On API 33, ungranted READ_MEDIA_AUDIO returns false",
            PermissionManager.hasAudioStoragePermission(fakeContext, sdkInt = 33)
        )

        grantedSet.add(Manifest.permission.READ_MEDIA_AUDIO)
        assertTrue(
            "On API 33, granted READ_MEDIA_AUDIO returns true",
            PermissionManager.hasAudioStoragePermission(fakeContext, sdkInt = 33)
        )

        // Below API 33, broad media audio permission is not required (SAF is used)
        val requiredPerm32 = PermissionManager.getRequiredAudioStoragePermission(sdkInt = 32)
        assertNull(requiredPerm32)
        assertTrue(
            "Below API 33, hasAudioStoragePermission returns true",
            PermissionManager.hasAudioStoragePermission(fakeContext, sdkInt = 32)
        )
    }

    @Test
    fun testRequiredOnboardingPermissionsPerApi() {
        // On API 37: both ACCESS_LOCAL_NETWORK and POST_NOTIFICATIONS
        val perms37 = PermissionManager.getRequiredOnboardingPermissions(sdkInt = 37)
        assertTrue(perms37.contains(PermissionManager.PERMISSION_ACCESS_LOCAL_NETWORK))
        assertTrue(perms37.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertEquals(2, perms37.size)

        // On API 33–36: only POST_NOTIFICATIONS
        val perms34 = PermissionManager.getRequiredOnboardingPermissions(sdkInt = 34)
        assertFalse(perms34.contains(PermissionManager.PERMISSION_ACCESS_LOCAL_NETWORK))
        assertTrue(perms34.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertEquals(1, perms34.size)

        // On API 30–32: empty list (no runtime onboarding permissions required)
        val perms30 = PermissionManager.getRequiredOnboardingPermissions(sdkInt = 30)
        assertTrue(perms30.isEmpty())
    }

    @Test
    fun testGetMissingPermissions() {
        val fakeContext = FakeContext()
        val requested = listOf(
            PermissionManager.PERMISSION_ACCESS_LOCAL_NETWORK,
            Manifest.permission.POST_NOTIFICATIONS
        )

        // On API 37 with none granted, both are missing
        val missing37 = PermissionManager.getMissingPermissions(fakeContext, requested, sdkInt = 37)
        assertEquals(2, missing37.size)

        // Grant one
        grantedSet.add(Manifest.permission.POST_NOTIFICATIONS)
        val missingAfterOneGranted = PermissionManager.getMissingPermissions(fakeContext, requested, sdkInt = 37)
        assertEquals(1, missingAfterOneGranted.size)
        assertEquals(PermissionManager.PERMISSION_ACCESS_LOCAL_NETWORK, missingAfterOneGranted[0])

        // On API 30, both are automatically permitted
        val missing30 = PermissionManager.getMissingPermissions(fakeContext, requested, sdkInt = 30)
        assertTrue(missing30.isEmpty())
    }

    @Test
    fun testRationalesAreMeaningfulAndPresent() {
        val networkRationale = PermissionManager.getRationale(PermissionManager.PERMISSION_ACCESS_LOCAL_NETWORK)
        val notifRationale = PermissionManager.getRationale(Manifest.permission.POST_NOTIFICATIONS)
        val storageRationale = PermissionManager.getRationale(Manifest.permission.READ_MEDIA_AUDIO)
        val captureRationale = PermissionManager.getRationale(Manifest.permission.RECORD_AUDIO)

        assertNotNull(networkRationale)
        assertTrue(networkRationale.contains("local network", ignoreCase = true))

        assertNotNull(notifRationale)
        assertTrue(notifRationale.contains("notification", ignoreCase = true))

        assertNotNull(storageRationale)
        assertTrue(storageRationale.contains("audio", ignoreCase = true))

        assertNotNull(captureRationale)
        assertTrue(captureRationale.contains("recording", ignoreCase = true))
    }
}
