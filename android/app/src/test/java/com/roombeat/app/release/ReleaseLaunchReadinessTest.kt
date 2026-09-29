package com.roombeat.app.release

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit verification test suite for Sub-phase v1.0.3:
 * Launch Readiness, Privacy Policy & Human-Run Deployment Checklist.
 *
 * Validates:
 * 1. Offline in-app privacy policy document exists in assets and contains all zero-telemetry
 *    and local network disclosures.
 * 2. Offline in-app open source licenses document exists in assets with accurate MIT,
 *    Google Oboe (Apache 2.0), Xiph Opus (BSD), and Spotify SDK attributions.
 * 3. Web privacy policy page (site/src/pages/privacy.astro) exists with complete legal disclosures.
 * 4. Application Gradle release configuration declares production versionName "1.0.0",
 *    minSdk 30, and compileSdk 35.
 * 5. Root repository LICENSE file is present with the MIT License and copyright notice.
 */
class ReleaseLaunchReadinessTest {

    private fun resolveFile(vararg paths: String): File? {
        val candidates = paths.flatMap { p ->
            listOf(
                File(p),
                File("../$p"),
                File("../../$p"),
                File("android/$p"),
                File("android/app/$p")
            )
        }
        return candidates.firstOrNull { it.exists() }
    }

    @Test
    fun testInAppPrivacyPolicyAssetContent() {
        val policyFile = resolveFile(
            "src/main/assets/privacy_policy.html",
            "app/src/main/assets/privacy_policy.html",
            "android/app/src/main/assets/privacy_policy.html"
        )
        assertNotNull("privacy_policy.html must exist in assets", policyFile)
        val content = policyFile!!.readText()

        assertTrue("Must declare RoomBeat Privacy Policy", content.contains("RoomBeat Privacy Policy"))
        assertTrue("Must state zero telemetry / data collection", content.contains("Zero Telemetry") || content.contains("zero-collection"))
        assertTrue("Must mention local multicast group", content.contains("239.255.42.99"))
        assertTrue("Must mention AudioPlaybackCaptureConfiguration", content.contains("AudioPlaybackCaptureConfiguration"))
        assertTrue("Must state audio is never written to disk or recorded", content.contains("never written to persistent disk"))
        assertTrue("Must mention Spotify App Remote privacy isolation", content.contains("Spotify"))
        assertTrue("Must disclose Camera QR code scanning privacy", content.contains("CAMERA"))
        assertTrue("Must disclose Android 17 ACCESS_LOCAL_NETWORK", content.contains("ACCESS_LOCAL_NETWORK"))
        assertTrue("Must declare MIT License", content.contains("MIT License") || content.contains("MIT LICENSE"))
    }

    @Test
    fun testInAppLicensesAssetContent() {
        val licensesFile = resolveFile(
            "src/main/assets/licenses.html",
            "app/src/main/assets/licenses.html",
            "android/app/src/main/assets/licenses.html"
        )
        assertNotNull("licenses.html must exist in assets", licensesFile)
        val content = licensesFile!!.readText()

        assertTrue("Must attribute RoomBeat MIT License", content.contains("RoomBeat") && content.contains("MIT License"))
        assertTrue("Must attribute Google Oboe", content.contains("Google Oboe") && content.contains("Apache License"))
        assertTrue("Must attribute Xiph Opus codec", content.contains("Opus") && content.contains("Xiph.Org"))
        assertTrue("Must attribute Spotify App Remote SDK", content.contains("Spotify"))
        assertTrue("Must attribute AndroidX / Jetpack Compose", content.contains("Jetpack Compose"))
        assertTrue("Must attribute ZXing / MLKit", content.contains("ZXing"))
        assertTrue("Must attribute Typography", content.contains("Cabinet Grotesk") && content.contains("JetBrains Mono"))
    }

    @Test
    fun testWebPrivacyPolicyPageContent() {
        val astroPolicyFile = resolveFile(
            "site/src/pages/privacy.astro",
            "../../site/src/pages/privacy.astro",
            "../site/src/pages/privacy.astro"
        )
        assertNotNull("privacy.astro must exist in site/src/pages", astroPolicyFile)
        val content = astroPolicyFile!!.readText()

        assertTrue("Must declare RoomBeat Privacy Policy in web page", content.contains("RoomBeat Privacy Policy"))
        assertTrue("Must declare 100% offline local network", content.contains("100% Offline Local Network") || content.contains("100% offline"))
        assertTrue("Must document multicast UDP pipeline", content.contains("239.255.42.99:4242"))
        assertTrue("Must document volatile RAM audio processing", content.contains("volatile RAM") || content.contains("never written to persistent disk"))
        assertTrue("Must link to WantedChip GitHub repository", content.contains("WantedChip/RoomBeat"))
    }

    @Test
    fun testProductionReleaseVersionAndSdkConfiguration() {
        val buildGradleFile = resolveFile(
            "build.gradle.kts",
            "app/build.gradle.kts",
            "android/app/build.gradle.kts"
        )
        assertNotNull("build.gradle.kts must exist", buildGradleFile)
        val content = buildGradleFile!!.readText()

        assertTrue("versionName must be 1.1.0 for Phase v1.1", content.contains("versionName = \"1.1.0\""))
        assertTrue("versionCode must be 2", content.contains("versionCode = 2"))
        assertTrue("minSdk must be 30", content.contains("minSdk = 30"))
        assertTrue("targetSdk must be 35", content.contains("targetSdk = 35"))
        assertTrue("compileSdk must be 35", content.contains("compileSdk = 35"))
    }

    @Test
    fun testManifestDeclaresLauncherActivity() {
        val manifestFile = resolveFile(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
            "android/app/src/main/AndroidManifest.xml"
        )
        assertNotNull("AndroidManifest.xml must exist", manifestFile)
        val content = manifestFile!!.readText()

        assertTrue("Must declare MainActivity", content.contains("android:name=\".MainActivity\""))
        assertTrue("Must declare ACTION_MAIN", content.contains("android.intent.action.MAIN"))
        assertTrue("Must declare CATEGORY_LAUNCHER", content.contains("android.intent.category.LAUNCHER"))
        assertTrue("MainActivity must be exported", content.contains("android:exported=\"true\""))
    }

    @Test
    fun testRootLicenseFilePresentAndValid() {
        val licenseFile = resolveFile(
            "LICENSE",
            "../LICENSE",
            "../../LICENSE"
        )
        assertNotNull("Root LICENSE must exist", licenseFile)
        val content = licenseFile!!.readText()

        assertTrue("Must be MIT License", content.contains("MIT License"))
        assertTrue("Must contain copyright WantedChip", content.contains("WantedChip"))
        assertTrue("Must contain 2026", content.contains("2026"))
    }
}
