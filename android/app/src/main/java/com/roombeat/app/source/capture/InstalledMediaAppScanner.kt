package com.roombeat.app.source.capture

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Category classification for installed media player applications.
 */
enum class MediaAppCategory {
    /** Music and podcast players (e.g. Poweramp, Foobar2000, local music). */
    AUDIO,

    /** Video players (e.g. YouTube, VLC, MX Player). */
    VIDEO,

    /** Cloud audio and streaming providers (e.g. Spotify, SoundCloud, YouTube Music, Tidal). */
    STREAMING,

    /** Other media-capable applications. */
    OTHER
}

/**
 * Descriptor for an installed third-party media player application.
 *
 * @param packageName Android package name (e.g. "org.videolan.vlc").
 * @param appName Human-readable display label (e.g. "VLC").
 * @param icon Application icon drawable (or null if unavailable).
 * @param launchIntent Explicit or launcher intent to start the application.
 * @param category Inferred media category.
 * @param isKnownOptOutApp True if the app is known to set AudioAttributes.FLAG_NO_SYSTEM_CAPTURE.
 */
data class InstalledMediaApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val launchIntent: Intent? = null,
    val category: MediaAppCategory = MediaAppCategory.AUDIO,
    val isKnownOptOutApp: Boolean = false
)

/**
 * Lightweight representation of resolved activity metadata returned by [PackageManagerFacade].
 */
data class ResolveInfoFacade(
    val packageName: String,
    val activityName: String? = null,
    val label: String? = null,
    val icon: Drawable? = null
)

/**
 * Query descriptor specifying intent filters for media player activity discovery.
 */
data class MediaIntentQuery(
    val action: String,
    val mimeType: String? = null,
    val category: String? = null
) {
    fun toIntent(): Intent {
        return Intent(action).apply {
            mimeType?.let { setType(it) }
            category?.let { addCategory(it) }
        }
    }
}

/**
 * Decoupled facade interface abstracting Android's [PackageManager] for 100% deterministic
 * JVM unit testing without native Android stubs or emulator dependencies.
 */
interface PackageManagerFacade {
    /**
     * Queries activities that match the given [MediaIntentQuery].
     */
    fun queryIntentActivities(query: MediaIntentQuery, flags: Int = 0): List<ResolveInfoFacade>

    /**
     * Queries activities that match the given [Intent].
     */
    fun queryIntentActivities(intent: Intent, flags: Int = 0): List<ResolveInfoFacade>

    /**
     * Returns the launch intent for the given package name, or null if not launchable.
     */
    fun getLaunchIntentForPackage(packageName: String): Intent?

    /**
     * Returns the user-facing display label for the application.
     */
    fun getApplicationLabel(packageName: String): String

    /**
     * Returns the icon for the application, or null if unavailable.
     */
    fun getApplicationIcon(packageName: String): Drawable?

    /**
     * Returns whether the package is currently installed on the system.
     */
    fun isPackageInstalled(packageName: String): Boolean

    /**
     * Returns the category code for the application (e.g. ApplicationInfo.CATEGORY_AUDIO, CATEGORY_VIDEO),
     * or -1 if undefined.
     */
    fun getApplicationCategory(packageName: String): Int
}

/**
 * Production implementation of [PackageManagerFacade] delegating to Android's [PackageManager].
 */
class DefaultPackageManagerFacade(
    private val packageManager: PackageManager
) : PackageManagerFacade {

    override fun queryIntentActivities(query: MediaIntentQuery, flags: Int): List<ResolveInfoFacade> {
        val intent = query.toIntent()
        return queryIntentActivities(intent, flags)
    }

    override fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfoFacade> {
        return try {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, flags)
            }
            list.mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                val act = resolveInfo.activityInfo?.name
                val label = try {
                    resolveInfo.loadLabel(packageManager).toString()
                } catch (_: Exception) {
                    null
                }
                val icon = try {
                    resolveInfo.loadIcon(packageManager)
                } catch (_: Exception) {
                    null
                }
                ResolveInfoFacade(
                    packageName = pkg,
                    activityName = act,
                    label = label,
                    icon = icon
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun getLaunchIntentForPackage(packageName: String): Intent? {
        return try {
            packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: Exception) {
            null
        }
    }

    override fun getApplicationLabel(packageName: String): String {
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    override fun getApplicationIcon(packageName: String): Drawable? {
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }

    override fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun getApplicationCategory(packageName: String): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0)
                }
                appInfo.category
            } else {
                -1
            }
        } catch (_: Exception) {
            -1
        }
    }
}

/**
 * Scanner utility detecting installed third-party media players (YouTube, VLC, SoundCloud, Spotify, etc.)
 * for RoomBeat's quick-launch drawer.
 *
 * Requirements:
 * - Scans applications handling audio/video intents or [ApplicationInfo.CATEGORY_AUDIO]/[ApplicationInfo.CATEGORY_VIDEO].
 * - Cross-references known popular audio and video packages to ensure discovery even with OEM manifest variations.
 * - Filters out RoomBeat itself ([selfPackageName]) to prevent self-referential capture loops.
 * - Flags known opt-out applications that set AudioAttributes.FLAG_NO_SYSTEM_CAPTURE.
 * - Deduplicates results and sorts audio/streaming sources first, then alphabetically.
 */
class InstalledMediaAppScanner(
    private val packageManagerFacade: PackageManagerFacade,
    val selfPackageName: String = DEFAULT_SELF_PACKAGE
) {
    companion object {
        const val DEFAULT_SELF_PACKAGE = "com.roombeat.app"

        /**
         * Known popular audio player packages.
         */
        val KNOWN_AUDIO_PACKAGES = setOf(
            "com.spotify.music",
            "com.soundcloud.android",
            "com.google.android.apps.youtube.music",
            "com.apple.android.music",
            "com.aspiro.tidal",
            "deezer.android.app",
            "com.bandcamp.android",
            "com.amazon.mp3",
            "com.maxmpz.audioplayer",
            "com.foobar2000.foobar2000",
            "gonemad.gmmp",
            "com.tbig.playerpro",
            "ch.blinkenlights.android.medialibrary",
            "org.schabi.newpipe",
            "tunein.player",
            "com.audible.application",
            "com.podcastaddict",
            "fm.player"
        )

        /**
         * Known popular video player packages.
         */
        val KNOWN_VIDEO_PACKAGES = setOf(
            "com.google.android.youtube",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "com.plexapp.android",
            "org.xbmc.kodi"
        )

        /**
         * Known cloud audio streaming packages.
         */
        val KNOWN_STREAMING_PACKAGES = setOf(
            "com.spotify.music",
            "com.soundcloud.android",
            "com.google.android.apps.youtube.music",
            "com.apple.android.music",
            "com.aspiro.tidal",
            "deezer.android.app",
            "com.bandcamp.android",
            "com.amazon.mp3",
            "tunein.player"
        )

        /**
         * Packages known to potentially opt out of system audio capture via
         * AudioAttributes.FLAG_NO_SYSTEM_CAPTURE or DRM restrictions.
         */
        val KNOWN_OPT_OUT_PACKAGES = setOf(
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus",
            "com.hbo.hbonow",
            "com.apple.android.music"
        )

        /**
         * Standard intent queries used to discover media players.
         */
        val MEDIA_INTENT_QUERIES = listOf(
            MediaIntentQuery(action = Intent.ACTION_VIEW, mimeType = "audio/*"),
            MediaIntentQuery(action = Intent.ACTION_VIEW, mimeType = "video/*"),
            MediaIntentQuery(action = Intent.ACTION_MAIN, category = Intent.CATEGORY_APP_MUSIC),
            MediaIntentQuery(action = "android.media.action.MEDIA_PLAY_FROM_SEARCH"),
            MediaIntentQuery(action = "android.intent.action.MUSIC_PLAYER")
        )
    }

    /**
     * Secondary constructor for standard Android Context.
     */
    constructor(context: Context, selfPackageName: String = context.packageName) :
            this(DefaultPackageManagerFacade(context.packageManager), selfPackageName)

    /**
     * Internal discovery origin tracking for category inference.
     */
    private enum class DiscoverySource {
        AUDIO_INTENT,
        VIDEO_INTENT,
        MUSIC_CATEGORY_INTENT,
        KNOWN_PACKAGE
    }

    /**
     * Scans and returns all installed third-party media players, deduplicated and sorted.
     */
    fun scanInstalledMediaApps(): List<InstalledMediaApp> {
        val discoveredPackages = mutableMapOf<String, DiscoverySource>()

        // 1. Discover via standard media intent queries
        for (query in MEDIA_INTENT_QUERIES) {
            val querySource = when {
                query.mimeType == "video/*" -> DiscoverySource.VIDEO_INTENT
                query.category == Intent.CATEGORY_APP_MUSIC -> DiscoverySource.MUSIC_CATEGORY_INTENT
                else -> DiscoverySource.AUDIO_INTENT
            }

            val matches = packageManagerFacade.queryIntentActivities(query)
            for (match in matches) {
                val pkg = match.packageName
                if (isValidCandidate(pkg) && !discoveredPackages.containsKey(pkg)) {
                    discoveredPackages[pkg] = querySource
                }
            }
        }

        // 2. Discover via known media packages to ensure detection across OEM skins
        val allKnown = KNOWN_AUDIO_PACKAGES + KNOWN_VIDEO_PACKAGES
        for (pkg in allKnown) {
            if (isValidCandidate(pkg) && !discoveredPackages.containsKey(pkg)) {
                if (packageManagerFacade.isPackageInstalled(pkg)) {
                    discoveredPackages[pkg] = DiscoverySource.KNOWN_PACKAGE
                }
            }
        }

        // 3. Transform to InstalledMediaApp models
        val result = mutableListOf<InstalledMediaApp>()
        for ((pkg, source) in discoveredPackages) {
            val launchIntent = packageManagerFacade.getLaunchIntentForPackage(pkg)
            // Filter out packages that cannot be launched by the user
            if (launchIntent == null) continue

            val appName = packageManagerFacade.getApplicationLabel(pkg).ifBlank { pkg }
            val icon = packageManagerFacade.getApplicationIcon(pkg)
            val appCategoryCode = packageManagerFacade.getApplicationCategory(pkg)

            val category = inferCategory(pkg, source, appCategoryCode)
            val isKnownOptOut = KNOWN_OPT_OUT_PACKAGES.contains(pkg)

            result.add(
                InstalledMediaApp(
                    packageName = pkg,
                    appName = appName,
                    icon = icon,
                    launchIntent = launchIntent,
                    category = category,
                    isKnownOptOutApp = isKnownOptOut
                )
            )
        }

        // 4. Deterministic sorting: Audio & Streaming first, then Video, then Other, alphabetically within
        return result.sortedWith(
            compareBy<InstalledMediaApp> { app ->
                when (app.category) {
                    MediaAppCategory.AUDIO -> 0
                    MediaAppCategory.STREAMING -> 1
                    MediaAppCategory.VIDEO -> 2
                    MediaAppCategory.OTHER -> 3
                }
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
        )
    }

    /**
     * Checks if a specific package is installed and returns its [InstalledMediaApp] model, or null.
     */
    fun getApp(packageName: String): InstalledMediaApp? {
        if (!isValidCandidate(packageName)) return null
        if (!packageManagerFacade.isPackageInstalled(packageName)) return null

        val launchIntent = packageManagerFacade.getLaunchIntentForPackage(packageName) ?: return null
        val appName = packageManagerFacade.getApplicationLabel(packageName).ifBlank { packageName }
        val icon = packageManagerFacade.getApplicationIcon(packageName)
        val appCategoryCode = packageManagerFacade.getApplicationCategory(packageName)

        val category = inferCategory(packageName, DiscoverySource.KNOWN_PACKAGE, appCategoryCode)
        val isKnownOptOut = KNOWN_OPT_OUT_PACKAGES.contains(packageName)

        return InstalledMediaApp(
            packageName = packageName,
            appName = appName,
            icon = icon,
            launchIntent = launchIntent,
            category = category,
            isKnownOptOutApp = isKnownOptOut
        )
    }

    /**
     * Returns true if the package is installed on the system.
     */
    fun isAppInstalled(packageName: String): Boolean {
        return packageManagerFacade.isPackageInstalled(packageName)
    }

    private fun isValidCandidate(packageName: String): Boolean {
        return packageName.isNotBlank() && packageName != selfPackageName
    }

    private fun inferCategory(
        packageName: String,
        source: DiscoverySource,
        appCategoryCode: Int
    ): MediaAppCategory {
        return when {
            KNOWN_STREAMING_PACKAGES.contains(packageName) -> MediaAppCategory.STREAMING
            KNOWN_VIDEO_PACKAGES.contains(packageName) -> MediaAppCategory.VIDEO
            KNOWN_AUDIO_PACKAGES.contains(packageName) -> MediaAppCategory.AUDIO
            appCategoryCode == ApplicationInfo.CATEGORY_AUDIO -> MediaAppCategory.AUDIO
            appCategoryCode == ApplicationInfo.CATEGORY_VIDEO -> MediaAppCategory.VIDEO
            source == DiscoverySource.VIDEO_INTENT -> MediaAppCategory.VIDEO
            source == DiscoverySource.MUSIC_CATEGORY_INTENT -> MediaAppCategory.AUDIO
            source == DiscoverySource.AUDIO_INTENT -> MediaAppCategory.AUDIO
            else -> MediaAppCategory.AUDIO
        }
    }
}
