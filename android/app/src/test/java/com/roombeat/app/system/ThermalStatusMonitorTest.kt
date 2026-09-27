package com.roombeat.app.system

import android.os.PowerManager
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.audio.buffer.JitterBufferConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

class ThermalStatusMonitorTest {

    private class FakeThermalStatusProvider : ThermalStatusProvider {
        override var currentThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
        var registeredListener: ((Int) -> Unit)? = null
        var registerCount = 0
        var unregisterCount = 0

        override fun registerThermalListener(executor: Executor, listener: (Int) -> Unit): Boolean {
            registerCount++
            registeredListener = listener
            return true
        }

        override fun unregisterThermalListener(listener: (Int) -> Unit): Boolean {
            unregisterCount++
            if (registeredListener == listener) {
                registeredListener = null
            }
            return true
        }

        fun triggerStatusChange(newStatus: Int) {
            currentThermalStatus = newStatus
            registeredListener?.invoke(newStatus)
        }
    }

    private lateinit var fakeProvider: FakeThermalStatusProvider
    private lateinit var monitor: ThermalStatusMonitor

    @Before
    fun setUp() {
        fakeProvider = FakeThermalStatusProvider()
        monitor = ThermalStatusMonitor(provider = fakeProvider, baseDepthMs = 120)
    }

    @Test
    fun testInitialStatus_DefaultsToNone() {
        assertEquals(PowerManager.THERMAL_STATUS_NONE, monitor.currentThermalStatus)
        assertEquals("NONE", monitor.thermalState.value.statusName)
        assertFalse(monitor.isThrottling)
        assertEquals(120, monitor.recommendedBufferDepthMs)
    }

    @Test
    fun testThermalStatusTransitions_CalculatesCorrectJitterBufferDepths() {
        assertEquals(120, monitor.calculateRecommendedDepth(PowerManager.THERMAL_STATUS_NONE))
        assertEquals(120, monitor.calculateRecommendedDepth(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(160, monitor.calculateRecommendedDepth(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(200, monitor.calculateRecommendedDepth(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(240, monitor.calculateRecommendedDepth(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals(240, monitor.calculateRecommendedDepth(PowerManager.THERMAL_STATUS_EMERGENCY))
        assertEquals(240, monitor.calculateRecommendedDepth(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun testStartMonitoring_RegistersListenerAndEmitsUpdates() {
        var callbackReceived: ThermalStatusInfo? = null
        monitor.onThermalStatusChanged = { info ->
            callbackReceived = info
        }

        val started = monitor.startMonitoring()
        assertTrue(started)
        assertEquals(1, fakeProvider.registerCount)

        // Trigger MODERATE thermal event
        fakeProvider.triggerStatusChange(PowerManager.THERMAL_STATUS_MODERATE)

        assertEquals(PowerManager.THERMAL_STATUS_MODERATE, monitor.currentThermalStatus)
        assertTrue(monitor.isThrottling)
        assertFalse(monitor.thermalState.value.isSevereThrottling)
        assertEquals(160, monitor.recommendedBufferDepthMs)
        assertEquals("MODERATE", monitor.thermalState.value.statusName)

        assertEquals(PowerManager.THERMAL_STATUS_MODERATE, callbackReceived?.status)
        assertEquals(160, callbackReceived?.recommendedBufferDepthMs)

        // Trigger SEVERE thermal event
        fakeProvider.triggerStatusChange(PowerManager.THERMAL_STATUS_SEVERE)
        assertEquals(PowerManager.THERMAL_STATUS_SEVERE, monitor.currentThermalStatus)
        assertTrue(monitor.isThrottling)
        assertTrue(monitor.thermalState.value.isSevereThrottling)
        assertEquals(200, monitor.recommendedBufferDepthMs)
        assertEquals("SEVERE", monitor.thermalState.value.statusName)

        // Trigger CRITICAL thermal event
        fakeProvider.triggerStatusChange(PowerManager.THERMAL_STATUS_CRITICAL)
        assertEquals(PowerManager.THERMAL_STATUS_CRITICAL, monitor.currentThermalStatus)
        assertTrue(monitor.thermalState.value.isSevereThrottling)
        assertEquals(240, monitor.recommendedBufferDepthMs)

        // Device cools down to NONE
        fakeProvider.triggerStatusChange(PowerManager.THERMAL_STATUS_NONE)
        assertEquals(PowerManager.THERMAL_STATUS_NONE, monitor.currentThermalStatus)
        assertFalse(monitor.isThrottling)
        assertFalse(monitor.thermalState.value.isSevereThrottling)
        assertEquals(120, monitor.recommendedBufferDepthMs)
    }

    @Test
    fun testAttachedJitterBuffer_DynamicallyExpandsTargetDepthWithoutAudioDropouts() {
        val jitterBuffer = AudioJitterBuffer(targetDepthMs = 120)
        monitor.attachedJitterBuffer = jitterBuffer
        monitor.startMonitoring()

        assertEquals(120, jitterBuffer.targetDepthMs)

        // Pre-fill jitter buffer with 4 audio frames
        val dummyPcm = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES) { 0.5f }
        for (i in 0 until 4) {
            jitterBuffer.pushDecodedFrame(
                sequenceNumber = i.toLong(),
                presentationTimeUs = 1_000_000L + (i * 20_000L),
                pcmData = dummyPcm
            )
        }
        assertEquals(4, jitterBuffer.queuedFrames)

        // Device hits MODERATE thermal throttling
        fakeProvider.triggerStatusChange(PowerManager.THERMAL_STATUS_MODERATE)

        // Buffer target depth expanded to 160ms automatically
        assertEquals(160, jitterBuffer.targetDepthMs)
        // Queued frames must remain intact (no audio dropouts or resets)
        assertEquals(4, jitterBuffer.queuedFrames)

        // Device hits SEVERE thermal throttling
        fakeProvider.triggerStatusChange(PowerManager.THERMAL_STATUS_SEVERE)
        assertEquals(200, jitterBuffer.targetDepthMs)
        assertEquals(4, jitterBuffer.queuedFrames)

        // Device hits CRITICAL thermal throttling
        fakeProvider.triggerStatusChange(PowerManager.THERMAL_STATUS_CRITICAL)
        assertEquals(240, jitterBuffer.targetDepthMs)
        assertEquals(4, jitterBuffer.queuedFrames)

        // Clean teardown
        jitterBuffer.close()
        monitor.close()
    }

    @Test
    fun testStopMonitoring_UnregistersListenerCleanly() {
        monitor.startMonitoring()
        assertEquals(1, fakeProvider.registerCount)

        monitor.stopMonitoring()
        assertEquals(1, fakeProvider.unregisterCount)

        // Events after stop should not affect monitor
        fakeProvider.triggerStatusChange(PowerManager.THERMAL_STATUS_SEVERE)
        // Remains at initial or previous state before event
        assertEquals(PowerManager.THERMAL_STATUS_NONE, monitor.currentThermalStatus)
    }

    @Test
    fun testAutoCloseable_CleansUpResources() {
        monitor.startMonitoring()
        val jitterBuffer = AudioJitterBuffer(targetDepthMs = 120)
        monitor.attachedJitterBuffer = jitterBuffer

        monitor.close()
        assertEquals(1, fakeProvider.unregisterCount)
        assertEquals(null, monitor.attachedJitterBuffer)
        assertEquals(null, monitor.onThermalStatusChanged)

        jitterBuffer.close()
    }
}
