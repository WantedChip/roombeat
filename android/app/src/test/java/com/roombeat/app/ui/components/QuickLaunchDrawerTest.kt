package com.roombeat.app.ui.components

import android.content.Intent
import com.roombeat.app.source.capture.InstalledMediaApp
import com.roombeat.app.source.capture.InstalledMediaAppScanner
import com.roombeat.app.source.capture.MediaAppCategory
import com.roombeat.app.source.capture.MediaIntentQuery
import com.roombeat.app.source.capture.PackageManagerFacade
import com.roombeat.app.source.capture.ResolveInfoFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QuickLaunchDrawerTest {

    private class FakePackageManagerFacade : PackageManagerFacade {
        val installed = mutableSetOf<String>()
        val labels = mutableMapOf<String, String>()

        fun add(pkg: String, label: String) {
            installed.add(pkg)
            labels[pkg] = label
        }

        override fun queryIntentActivities(query: MediaIntentQuery, flags: Int): List<ResolveInfoFacade> = emptyList()
        override fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfoFacade> = emptyList()
        override fun getLaunchIntentForPackage(packageName: String): Intent? = Intent()
        override fun getApplicationLabel(packageName: String): String = labels[packageName] ?: packageName
        override fun getApplicationIcon(packageName: String) = null
        override fun isPackageInstalled(packageName: String): Boolean = installed.contains(packageName)
        override fun getApplicationCategory(packageName: String): Int = -1
    }

    private lateinit var fakeFacade: FakePackageManagerFacade
    private lateinit var scanner: InstalledMediaAppScanner

    @Before
    fun setUp() {
        fakeFacade = FakePackageManagerFacade()
        scanner = InstalledMediaAppScanner(fakeFacade)
    }

    @Test
    fun testDrawerState_DefaultInitialization() {
        val state = QuickLaunchDrawerState()
        assertFalse(state.isOpen)
        assertTrue(state.installedApps.isEmpty())
        assertFalse(state.isLoading)
        assertNull(state.selectedApp)
    }

    @Test
    fun testDrawerState_OpenAndDismiss() {
        val state = QuickLaunchDrawerState()

        state.open()
        assertTrue(state.isOpen)

        state.dismiss()
        assertFalse(state.isOpen)
    }

    @Test
    fun testDrawerState_ScanPopulatesInstalledApps() {
        fakeFacade.add("com.spotify.music", "Spotify")
        fakeFacade.add("org.videolan.vlc", "VLC")

        val state = QuickLaunchDrawerState(scanner = scanner)
        assertFalse(state.isLoading)

        state.scan(scanner)
        assertFalse(state.isLoading)
        assertEquals(2, state.installedApps.size)

        val spotify = state.installedApps.first { it.packageName == "com.spotify.music" }
        assertEquals("Spotify", spotify.appName)
        assertEquals(MediaAppCategory.STREAMING, spotify.category)
    }

    @Test
    fun testInstalledMediaApp_Properties() {
        val launchIntent = Intent("android.intent.action.VIEW")
        val app = InstalledMediaApp(
            packageName = "org.videolan.vlc",
            appName = "VLC",
            icon = null,
            launchIntent = launchIntent,
            category = MediaAppCategory.VIDEO,
            isKnownOptOutApp = false
        )

        assertEquals("org.videolan.vlc", app.packageName)
        assertEquals("VLC", app.appName)
        assertNull(app.icon)
        assertEquals(launchIntent, app.launchIntent)
        assertEquals(MediaAppCategory.VIDEO, app.category)
        assertFalse(app.isKnownOptOutApp)
    }

    @Test
    fun testInstalledMediaApp_OptOutFlag() {
        val app = InstalledMediaApp(
            packageName = "com.apple.android.music",
            appName = "Apple Music",
            category = MediaAppCategory.STREAMING,
            isKnownOptOutApp = true
        )
        assertTrue(app.isKnownOptOutApp)
    }

    @Test
    fun testMediaAppCategoryEnumValues() {
        val categories = MediaAppCategory.values()
        assertEquals(4, categories.size)
        assertNotNull(MediaAppCategory.valueOf("AUDIO"))
        assertNotNull(MediaAppCategory.valueOf("VIDEO"))
        assertNotNull(MediaAppCategory.valueOf("STREAMING"))
        assertNotNull(MediaAppCategory.valueOf("OTHER"))
    }

    @Test
    fun testSafeDrawableToImageBitmap_HandlesNullGracefully() {
        val result = safeDrawableToImageBitmap(null)
        assertNull(result)
    }

    @Test
    fun testDrawerState_LifecycleSequence() {
        fakeFacade.add("com.soundcloud.android", "SoundCloud")
        val state = QuickLaunchDrawerState(scanner = scanner)

        // 1. Initially closed
        assertFalse(state.isOpen)

        // 2. Open drawer
        state.open()
        assertTrue(state.isOpen)

        // 3. Scan apps
        state.scan()
        assertEquals(1, state.installedApps.size)

        // 4. Select app
        val selected = state.installedApps[0]
        state.selectedApp = selected
        assertEquals("SoundCloud", state.selectedApp?.appName)

        // 5. Dismiss drawer
        state.dismiss()
        assertFalse(state.isOpen)
    }
}
