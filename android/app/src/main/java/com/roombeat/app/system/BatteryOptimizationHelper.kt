package com.roombeat.app.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.Locale

/**
 * Recognized OEM hardware manufacturers known for aggressive background process killing
 * and proprietary battery optimization systems ("Don't Kill My App").
 */
enum class OemManufacturer(val displayName: String, val isAggressive: Boolean) {
    XIAOMI("Xiaomi / MIUI / HyperOS", true),
    SAMSUNG("Samsung / One UI", true),
    HUAWEI("Huawei / Honor", true),
    OPPO_ONEPLUS("Oppo / OnePlus / Realme", true),
    VIVO("Vivo / iQOO", true),
    ASUS("Asus / ROG", false),
    GENERIC("Standard Android / Pixel / Motorola / Sony", false)
}

/**
 * Guidance information instructing users how to prevent aggressive OEM battery savers
 * from killing background audio capture or throttling UDP multicast streaming when the screen locks.
 */
data class OemBatteryGuidance(
    val manufacturer: OemManufacturer,
    val title: String,
    val description: String,
    val steps: List<String>,
    val isAggressiveOem: Boolean = manufacturer.isAggressive
)

/**
 * Current battery optimization exemption status and diagnostic metadata.
 */
data class BatteryOptimizationStatus(
    val isIgnoringBatteryOptimizations: Boolean,
    val manufacturer: OemManufacturer,
    val guidance: OemBatteryGuidance
)

/**
 * Helper utility for detecting battery optimization restrictions, querying OEM manufacturers,
 * providing manufacturer-specific battery saver bypass guidance, and generating/launching
 * appropriate system settings intents.
 *
 * Essential for continuous background audio multicast streaming when phone display sleeps
 * or locks.
 */
object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptHelper"

    /**
     * Checks if the app is currently exempt from standard Android battery optimizations.
     *
     * @param context Application or activity context.
     * @return true if battery optimizations are ignored, false otherwise.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Detects the OEM device manufacturer from Build properties.
     */
    fun detectOemManufacturer(
        manufacturer: String = Build.MANUFACTURER,
        brand: String = Build.BRAND
    ): OemManufacturer {
        val m = manufacturer.lowercase(Locale.ROOT)
        val b = brand.lowercase(Locale.ROOT)

        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
            b.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> OemManufacturer.XIAOMI

            m.contains("samsung") || b.contains("samsung") -> OemManufacturer.SAMSUNG

            m.contains("huawei") || m.contains("honor") ||
            b.contains("huawei") || b.contains("honor") -> OemManufacturer.HUAWEI

            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") ||
            b.contains("oppo") || b.contains("oneplus") || b.contains("realme") -> OemManufacturer.OPPO_ONEPLUS

            m.contains("vivo") || m.contains("iqoo") ||
            b.contains("vivo") || b.contains("iqoo") -> OemManufacturer.VIVO

            m.contains("asus") || b.contains("asus") -> OemManufacturer.ASUS

            else -> OemManufacturer.GENERIC
        }
    }

    /**
     * Retrieves manufacturer-specific guidance instructions for preventing background sleep throttling.
     */
    fun getOemGuidance(manufacturer: OemManufacturer = detectOemManufacturer()): OemBatteryGuidance {
        return when (manufacturer) {
            OemManufacturer.XIAOMI -> OemBatteryGuidance(
                manufacturer = OemManufacturer.XIAOMI,
                title = "MIUI / HyperOS Battery Restrictions",
                description = "Xiaomi devices restrict background multicast networking when the screen is locked.",
                steps = listOf(
                    "Open App Info for RoomBeat.",
                    "Set 'Battery Saver' to 'No restrictions'.",
                    "Enable 'Autostart' toggle in App permissions.",
                    "Lock RoomBeat in Recent Apps tray if needed."
                )
            )
            OemManufacturer.SAMSUNG -> OemBatteryGuidance(
                manufacturer = OemManufacturer.SAMSUNG,
                title = "Samsung One UI Battery Restrictions",
                description = "Samsung One UI may put background audio streaming services to sleep.",
                steps = listOf(
                    "Open Device Care -> Battery -> Background usage limits.",
                    "Add RoomBeat to 'Never sleeping apps'.",
                    "In App Info -> Battery, select 'Unrestricted'."
                )
            )
            OemManufacturer.HUAWEI -> OemBatteryGuidance(
                manufacturer = OemManufacturer.HUAWEI,
                title = "Huawei / Honor App Launch Management",
                description = "EMUI aggressively freezes network sockets for background applications.",
                steps = listOf(
                    "Open Battery -> App launch.",
                    "Find RoomBeat and toggle off 'Manage automatically'.",
                    "Enable 'Auto-launch', 'Secondary launch', and 'Run in background'."
                )
            )
            OemManufacturer.OPPO_ONEPLUS -> OemBatteryGuidance(
                manufacturer = OemManufacturer.OPPO_ONEPLUS,
                title = "OxygenOS / ColorOS Battery Optimization",
                description = "ColorOS freezes Wi-Fi sockets during screen sleep without optimization exemptions.",
                steps = listOf(
                    "Go to App Info -> Battery usage.",
                    "Enable 'Allow background activity' and 'Allow auto-launch'.",
                    "Set Battery optimization to 'Don't optimize'."
                )
            )
            OemManufacturer.VIVO -> OemBatteryGuidance(
                manufacturer = OemManufacturer.VIVO,
                title = "Vivo / FuntouchOS Background Power",
                description = "Vivo systems terminate background UDP networking under default power profiles.",
                steps = listOf(
                    "Go to Settings -> Battery -> High background power consumption.",
                    "Toggle ON RoomBeat to allow continuous streaming.",
                    "Enable Autostart in Permission Management."
                )
            )
            OemManufacturer.ASUS -> OemBatteryGuidance(
                manufacturer = OemManufacturer.ASUS,
                title = "ROG / ZenUI PowerMaster Restrictions",
                description = "Asus PowerMaster may limit background network sockets.",
                steps = listOf(
                    "Open Mobile Manager / PowerMaster -> Auto-start Manager.",
                    "Allow RoomBeat to auto-start and run in background.",
                    "In App Info -> Battery, set to 'Unrestricted'."
                )
            )
            OemManufacturer.GENERIC -> OemBatteryGuidance(
                manufacturer = OemManufacturer.GENERIC,
                title = "Android Battery Optimization",
                description = "Standard Android battery optimization may throttle background audio capture.",
                steps = listOf(
                    "Open Settings -> Apps -> RoomBeat -> App battery usage.",
                    "Select 'Unrestricted' for continuous background streaming without throttling."
                )
            )
        }
    }

    /**
     * Checks current battery optimization status and returns complete telemetry.
     */
    fun checkStatus(context: Context): BatteryOptimizationStatus {
        val exempt = isIgnoringBatteryOptimizations(context)
        val mfr = detectOemManufacturer()
        val guidance = getOemGuidance(mfr)
        return BatteryOptimizationStatus(
            isIgnoringBatteryOptimizations = exempt,
            manufacturer = mfr,
            guidance = guidance
        )
    }

    /**
     * Creates intent to directly prompt user to ignore battery optimizations for this app.
     * Requires [android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS].
     */
    fun createRequestIgnoreBatteryOptimizationsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
    }

    /**
     * Creates intent opening the system-wide Battery Optimization settings screen.
     */
    fun createIgnoreBatteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /**
     * Creates intent opening the application details settings screen.
     */
    fun createAppDetailsSettingsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
    }

    /**
     * Attempts to create OEM-specific autostart or power manager intent if available.
     */
    fun createOemAutostartSettingsIntent(context: Context): Intent? {
        val mfr = detectOemManufacturer()

        val candidateIntents = when (mfr) {
            OemManufacturer.XIAOMI -> listOf(
                Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                Intent().setClassName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
            )
            OemManufacturer.SAMSUNG -> listOf(
                Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                Intent().setClassName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
            )
            OemManufacturer.HUAWEI -> listOf(
                Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
            )
            OemManufacturer.OPPO_ONEPLUS -> listOf(
                Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                Intent().setClassName("com.oplus.battery", "com.oplus.battery.PowerControlActivity")
            )
            OemManufacturer.VIVO -> listOf(
                Intent().setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            )
            else -> emptyList()
        }

        val packageManager = context.packageManager
        for (intent in candidateIntents) {
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    return intent
                }
            } catch (e: Exception) {
                // Ignore and try next
            }
        }
        return null
    }

    /**
     * Requests battery optimization exemption using fallback strategy:
     * 1. Direct prompt (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
     * 2. Battery optimization settings (ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
     * 3. App Details settings (ACTION_APPLICATION_DETAILS_SETTINGS)
     *
     * @return true if an intent was successfully resolved and launched.
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val packageName = context.packageName

        // 1. If context is an Activity, try direct prompt first
        if (context is Activity) {
            try {
                val directIntent = createRequestIgnoreBatteryOptimizationsIntent(packageName)
                if (directIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(directIntent)
                    Log.i(TAG, "Launched ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct ignore battery optimizations request failed: ${e.message}")
            }
        }

        // 2. Try general Battery Optimization Settings screen
        try {
            val settingsIntent = createIgnoreBatteryOptimizationSettingsIntent()
            if (settingsIntent.resolveActivity(context.packageManager) != null) {
                if (context !is Activity) settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
                Log.i(TAG, "Launched ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Battery optimization settings intent failed: ${e.message}")
        }

        // 3. Try OEM-specific autostart screen
        val oemIntent = createOemAutostartSettingsIntent(context)
        if (oemIntent != null) {
            try {
                context.startActivity(oemIntent)
                Log.i(TAG, "Launched OEM autostart settings intent")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "OEM autostart intent launch failed: ${e.message}")
            }
        }

        // 4. Fallback to App Details settings
        try {
            val appDetailsIntent = createAppDetailsSettingsIntent(packageName)
            if (context !is Activity) appDetailsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(appDetailsIntent)
            Log.i(TAG, "Launched ACTION_APPLICATION_DETAILS_SETTINGS")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app details settings: ${e.message}", e)
            return false
        }
    }
}
