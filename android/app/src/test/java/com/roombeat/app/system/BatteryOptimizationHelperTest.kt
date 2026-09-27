package com.roombeat.app.system

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationHelperTest {

    @Test
    fun testDetectOemManufacturer_CorrectlyClassifiesKnownBrands() {
        assertEquals(
            OemManufacturer.XIAOMI,
            BatteryOptimizationHelper.detectOemManufacturer(manufacturer = "Xiaomi", brand = "Redmi")
        )
        assertEquals(
            OemManufacturer.XIAOMI,
            BatteryOptimizationHelper.detectOemManufacturer(manufacturer = "Xiaomi", brand = "POCO")
        )
        assertEquals(
            OemManufacturer.SAMSUNG,
            BatteryOptimizationHelper.detectOemManufacturer(manufacturer = "Samsung", brand = "samsung")
        )
        assertEquals(
            OemManufacturer.HUAWEI,
            BatteryOptimizationHelper.detectOemManufacturer(manufacturer = "HUAWEI", brand = "honor")
        )
        assertEquals(
            OemManufacturer.OPPO_ONEPLUS,
            BatteryOptimizationHelper.detectOemManufacturer(manufacturer = "OnePlus", brand = "OnePlus")
        )
        assertEquals(
            OemManufacturer.OPPO_ONEPLUS,
            BatteryOptimizationHelper.detectOemManufacturer(manufacturer = "OPPO", brand = "realme")
        )
        assertEquals(
            OemManufacturer.VIVO,
            BatteryOptimizationHelper.detectOemManufacturer(manufacturer = "vivo", brand = "iQOO")
        )
        assertEquals(
            OemManufacturer.ASUS,
            BatteryOptimizationHelper.detectOemManufacturer(manufacturer = "asus", brand = "asus")
        )
        assertEquals(
            OemManufacturer.GENERIC,
            BatteryOptimizationHelper.detectOemManufacturer(manufacturer = "Google", brand = "Pixel")
        )
        assertEquals(
            OemManufacturer.GENERIC,
            BatteryOptimizationHelper.detectOemManufacturer(manufacturer = "Motorola", brand = "moto")
        )
        assertEquals(
            OemManufacturer.GENERIC,
            BatteryOptimizationHelper.detectOemManufacturer(manufacturer = "Sony", brand = "Xperia")
        )
    }

    @Test
    fun testOemAggressivenessClassification() {
        assertTrue("Xiaomi should be flagged as aggressive OEM", OemManufacturer.XIAOMI.isAggressive)
        assertTrue("Samsung should be flagged as aggressive OEM", OemManufacturer.SAMSUNG.isAggressive)
        assertTrue("Huawei should be flagged as aggressive OEM", OemManufacturer.HUAWEI.isAggressive)
        assertTrue("OnePlus/Oppo should be flagged as aggressive OEM", OemManufacturer.OPPO_ONEPLUS.isAggressive)
        assertTrue("Vivo should be flagged as aggressive OEM", OemManufacturer.VIVO.isAggressive)
        assertFalse("Asus is not classified as overly aggressive", OemManufacturer.ASUS.isAggressive)
        assertFalse("Generic/Pixel is not classified as aggressive", OemManufacturer.GENERIC.isAggressive)
    }

    @Test
    fun testGetOemGuidance_ProvidesSpecificActionableSteps() {
        for (mfr in OemManufacturer.values()) {
            val guidance = BatteryOptimizationHelper.getOemGuidance(mfr)
            assertNotNull(guidance.title)
            assertNotNull(guidance.description)
            assertTrue("Guidance should contain at least 1 step", guidance.steps.isNotEmpty())
            assertEquals(mfr, guidance.manufacturer)
            assertEquals(mfr.isAggressive, guidance.isAggressiveOem)
        }

        val xiaomiGuidance = BatteryOptimizationHelper.getOemGuidance(OemManufacturer.XIAOMI)
        assertTrue(xiaomiGuidance.steps.any { it.contains("No restrictions") })
        assertTrue(xiaomiGuidance.steps.any { it.contains("Autostart") })

        val samsungGuidance = BatteryOptimizationHelper.getOemGuidance(OemManufacturer.SAMSUNG)
        assertTrue(samsungGuidance.steps.any { it.contains("Never sleeping apps") })

        val huaweiGuidance = BatteryOptimizationHelper.getOemGuidance(OemManufacturer.HUAWEI)
        assertTrue(huaweiGuidance.steps.any { it.contains("Manage automatically") })
    }

    @Test
    fun testIntentCreation_DirectRequestIntent() {
        val intent = BatteryOptimizationHelper.createRequestIgnoreBatteryOptimizationsIntent("com.roombeat.app")
        assertNotNull("Intent should not be null", intent)
    }

    @Test
    fun testIntentCreation_GeneralSettingsIntent() {
        val intent = BatteryOptimizationHelper.createIgnoreBatteryOptimizationSettingsIntent()
        assertNotNull("Intent should not be null", intent)
    }

    @Test
    fun testIntentCreation_AppDetailsSettingsIntent() {
        val intent = BatteryOptimizationHelper.createAppDetailsSettingsIntent("com.roombeat.app")
        assertNotNull("Intent should not be null", intent)
    }
}
