package com.roombeat.app.source.capture

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InstalledMediaAppScannerTest {

    private class FakePackageManagerFacade : PackageManagerFacade {
        val installedPackages = mutableSetOf<String>()
        val launchIntents = mutableMapOf<String, Intent>()
        val labels = mutableMapOf<String, String>()
        val icons = mutableMapOf<String, Drawable?>()
        val categories = mutableMapOf<String, Int>()
        val intentQueryMatches = mutableMapOf<MediaIntentQuery, MutableList<ResolveInfoFacade>>()
        var fallbackIntentMatch: ((Intent) -> List<ResolveInfoFacade>)? = null

        fun addApp(
            packageName: String,
            label: String,
            category: Int = -1,
            launchIntent: Intent? = Intent(),
            icon: Drawable? = null
        ) {
            installedPackages.add(packageName)
            labels[packageName] = label
            categories[packageName] = category
            if (launchIntent != null) {
                launchIntents[packageName] = launchIntent
            }
            icons[packageName] = icon
        }

        fun addIntentMatch(query: MediaIntentQuery, vararg matches: ResolveInfoFacade) {
            intentQueryMatches.getOrPut(query) { mutableListOf() }.addAll(matches)
        }

        override fun queryIntentActivities(query: MediaIntentQuery, flags: Int): List<ResolveInfoFacade> {
            return intentQueryMatches[query] ?: emptyList()
        }

        override fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfoFacade> {
            return fallbackIntentMatch?.invoke(intent) ?: emptyList()
        }

        override fun getLaunchIntentForPackage(packageName: String): Intent? {
            return launchIntents[packageName]
        }

        override fun getApplicationLabel(packageName: String): String {
            return labels[packageName] ?: packageName
        }

        override fun getApplicationIcon(packageName: String): Drawable? {
            return icons[packageName]
        }

        override fun isPackageInstalled(packageName: String): Boolean {
            return installedPackages.contains(packageName)
        }

        override fun getApplicationCategory(packageName: String): Int {
            return categories[packageName] ?: -1
        }
    }

    private lateinit var fakeFacade: FakePackageManagerFacade
    private lateinit var scanner: InstalledMediaAppScanner

    @Before
    fun setUp() {
        fakeFacade = FakePackageManagerFacade()
        scanner = InstalledMediaAppScanner(fakeFacade, selfPackageName = "com.roombeat.app")
    }

    @Test
    fun testEmptyScanReturnsEmptyList() {
        val apps = scanner.scanInstalledMediaApps()
        assertTrue(apps.isEmpty())
    }

    @Test
    fun testDiscoversKnownAudioAndStreamingPackages() {
        fakeFacade.addApp("com.spotify.music", "Spotify")
        fakeFacade.addApp("com.soundcloud.android", "SoundCloud")
        fakeFacade.addApp("com.maxmpz.audioplayer", "Poweramp")

        val apps = scanner.scanInstalledMediaApps()
        assertEquals(3, apps.size)

        val spotify = apps.first { it.packageName == "com.spotify.music" }
        assertEquals("Spotify", spotify.appName)
        assertEquals(MediaAppCategory.STREAMING, spotify.category)
        assertFalse(spotify.isKnownOptOutApp)

        val poweramp = apps.first { it.packageName == "com.maxmpz.audioplayer" }
        assertEquals("Poweramp", poweramp.appName)
        assertEquals(MediaAppCategory.AUDIO, poweramp.category)
    }

    @Test
    fun testDiscoversKnownVideoPackages() {
        fakeFacade.addApp("org.videolan.vlc", "VLC")
        fakeFacade.addApp("com.google.android.youtube", "YouTube")

        val apps = scanner.scanInstalledMediaApps()
        assertEquals(2, apps.size)

        val vlc = apps.first { it.packageName == "org.videolan.vlc" }
        assertEquals(MediaAppCategory.VIDEO, vlc.category)

        val yt = apps.first { it.packageName == "com.google.android.youtube" }
        assertEquals(MediaAppCategory.VIDEO, yt.category)
    }

    @Test
    fun testDiscoversAppsViaMediaIntentQueries() {
        val audioQuery = MediaIntentQuery(action = Intent.ACTION_VIEW, mimeType = "audio/*")
        fakeFacade.addApp("com.custom.audioplayer", "Custom Player")
        fakeFacade.addIntentMatch(
            audioQuery,
            ResolveInfoFacade(packageName = "com.custom.audioplayer", label = "Custom Player")
        )

        val apps = scanner.scanInstalledMediaApps()
        assertEquals(1, apps.size)
        assertEquals("com.custom.audioplayer", apps[0].packageName)
        assertEquals(MediaAppCategory.AUDIO, apps[0].category)
    }

    @Test
    fun testDiscoversAppsViaVideoIntentQueries() {
        val videoQuery = MediaIntentQuery(action = Intent.ACTION_VIEW, mimeType = "video/*")
        fakeFacade.addApp("com.custom.videoplayer", "Custom Video")
        fakeFacade.addIntentMatch(
            videoQuery,
            ResolveInfoFacade(packageName = "com.custom.videoplayer", label = "Custom Video")
        )

        val apps = scanner.scanInstalledMediaApps()
        assertEquals(1, apps.size)
        assertEquals("com.custom.videoplayer", apps[0].packageName)
        assertEquals(MediaAppCategory.VIDEO, apps[0].category)
    }

    @Test
    fun testExcludesSelfPackage() {
        // RoomBeat registers audio intent in manifest, but must not show up in third-party drawer
        fakeFacade.addApp("com.roombeat.app", "RoomBeat")
        fakeFacade.addApp("org.videolan.vlc", "VLC")

        val audioQuery = MediaIntentQuery(action = Intent.ACTION_VIEW, mimeType = "audio/*")
        fakeFacade.addIntentMatch(
            audioQuery,
            ResolveInfoFacade(packageName = "com.roombeat.app", label = "RoomBeat"),
            ResolveInfoFacade(packageName = "org.videolan.vlc", label = "VLC")
        )

        val apps = scanner.scanInstalledMediaApps()
        assertEquals(1, apps.size)
        assertEquals("org.videolan.vlc", apps[0].packageName)
        assertFalse(apps.any { it.packageName == "com.roombeat.app" })
    }

    @Test
    fun testExcludesAppsWithoutLaunchIntent() {
        // App is installed and matches intent, but has no launchable launcher activity
        fakeFacade.addApp("com.background.music.daemon", "Music Daemon", launchIntent = null)
        fakeFacade.addApp("com.spotify.music", "Spotify")

        val apps = scanner.scanInstalledMediaApps()
        assertEquals(1, apps.size)
        assertEquals("com.spotify.music", apps[0].packageName)
    }

    @Test
    fun testDeduplicatesPackagesAcrossMultipleQueries() {
        fakeFacade.addApp("org.videolan.vlc", "VLC")

        // Matches both audio/* and video/* intents and is in KNOWN_VIDEO_PACKAGES
        val audioQuery = MediaIntentQuery(action = Intent.ACTION_VIEW, mimeType = "audio/*")
        val videoQuery = MediaIntentQuery(action = Intent.ACTION_VIEW, mimeType = "video/*")
        fakeFacade.addIntentMatch(audioQuery, ResolveInfoFacade(packageName = "org.videolan.vlc"))
        fakeFacade.addIntentMatch(videoQuery, ResolveInfoFacade(packageName = "org.videolan.vlc"))

        val apps = scanner.scanInstalledMediaApps()
        assertEquals(1, apps.size)
        assertEquals("org.videolan.vlc", apps[0].packageName)
    }

    @Test
    fun testFlagsKnownOptOutApps() {
        fakeFacade.addApp("com.apple.android.music", "Apple Music")
        fakeFacade.addApp("com.spotify.music", "Spotify")

        val apps = scanner.scanInstalledMediaApps()
        val appleMusic = apps.first { it.packageName == "com.apple.android.music" }
        val spotify = apps.first { it.packageName == "com.spotify.music" }

        assertTrue(appleMusic.isKnownOptOutApp)
        assertFalse(spotify.isKnownOptOutApp)
    }

    @Test
    fun testDeterministicSorting_AudioAndStreamingBeforeVideo() {
        fakeFacade.addApp("org.videolan.vlc", "VLC") // Video
        fakeFacade.addApp("com.spotify.music", "Spotify") // Streaming
        fakeFacade.addApp("com.maxmpz.audioplayer", "Poweramp") // Audio
        fakeFacade.addApp("com.google.android.youtube", "YouTube") // Video

        val apps = scanner.scanInstalledMediaApps()
        assertEquals(4, apps.size)

        // Category order: AUDIO (0) -> STREAMING (1) -> VIDEO (2)
        assertEquals("Poweramp", apps[0].appName) // AUDIO
        assertEquals("Spotify", apps[1].appName)  // STREAMING
        assertEquals("VLC", apps[2].appName)      // VIDEO (alphabetical V vs Y)
        assertEquals("YouTube", apps[3].appName)  // VIDEO
    }

    @Test
    fun testGetAppReturnsSpecificApp() {
        fakeFacade.addApp("com.spotify.music", "Spotify")

        val app = scanner.getApp("com.spotify.music")
        assertNotNull(app)
        assertEquals("Spotify", app?.appName)
        assertEquals("com.spotify.music", app?.packageName)
        assertEquals(MediaAppCategory.STREAMING, app?.category)
    }

    @Test
    fun testGetAppReturnsNullForUninstalledOrExcludedApp() {
        fakeFacade.addApp("com.roombeat.app", "RoomBeat")

        assertNull(scanner.getApp("non.existent.package"))
        assertNull(scanner.getApp("com.roombeat.app"))
        assertNull(scanner.getApp(""))
    }

    @Test
    fun testIsAppInstalled() {
        fakeFacade.addApp("org.videolan.vlc", "VLC")

        assertTrue(scanner.isAppInstalled("org.videolan.vlc"))
        assertFalse(scanner.isAppInstalled("com.not.installed"))
    }

    @Test
    fun testApplicationCategoryInference() {
        // App discovered through general intent, but marked with Android 8+ category codes
        val musicCategoryQuery = MediaIntentQuery(action = Intent.ACTION_MAIN, category = Intent.CATEGORY_APP_MUSIC)
        fakeFacade.addApp(
            packageName = "com.oem.music",
            label = "OEM Music",
            category = ApplicationInfo.CATEGORY_AUDIO
        )
        fakeFacade.addIntentMatch(
            musicCategoryQuery,
            ResolveInfoFacade(packageName = "com.oem.music")
        )

        val apps = scanner.scanInstalledMediaApps()
        val oem = apps.first { it.packageName == "com.oem.music" }
        assertEquals(MediaAppCategory.AUDIO, oem.category)
    }
}
