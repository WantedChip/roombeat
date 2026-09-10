package com.roombeat.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ComponentModelsTest {

    @Test
    fun testBeaconStatusEnumValues() {
        val statuses = BeaconStatus.values()
        assertEquals(5, statuses.size)
        assertNotNull(BeaconStatus.valueOf("LOCKED"))
        assertNotNull(BeaconStatus.valueOf("CALIBRATING"))
        assertNotNull(BeaconStatus.valueOf("WARNING"))
        assertNotNull(BeaconStatus.valueOf("ERROR"))
        assertNotNull(BeaconStatus.valueOf("IDLE"))
    }

    @Test
    fun testTactileButtonVariantEnumValues() {
        val variants = TactileButtonVariant.values()
        assertEquals(3, variants.size)
        assertNotNull(TactileButtonVariant.valueOf("PRIMARY"))
        assertNotNull(TactileButtonVariant.valueOf("SURFACE"))
        assertNotNull(TactileButtonVariant.valueOf("DESTRUCTIVE"))
    }

    @Test
    fun testTelemetrySizeEnumValues() {
        val sizes = TelemetrySize.values()
        assertEquals(3, sizes.size)
        assertNotNull(TelemetrySize.valueOf("LARGE"))
        assertNotNull(TelemetrySize.valueOf("MEDIUM"))
        assertNotNull(TelemetrySize.valueOf("SMALL"))
    }
}
