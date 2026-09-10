package com.roombeat.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RoomBeatCaptureServiceTest {

    @Test
    fun testServiceConstants() {
        assertEquals("com.roombeat.app.action.START_CAPTURE", RoomBeatCaptureService.ACTION_START_CAPTURE)
        assertEquals("com.roombeat.app.action.STOP_CAPTURE", RoomBeatCaptureService.ACTION_STOP_CAPTURE)
        assertEquals("roombeat_audio_capture_channel", RoomBeatCaptureService.CHANNEL_ID)
        assertEquals("RoomBeat Audio Capture & Streaming", RoomBeatCaptureService.CHANNEL_NAME)
        assertEquals(1001, RoomBeatCaptureService.NOTIFICATION_ID)
    }

    @Test
    fun testServiceInstantiable() {
        val service = RoomBeatCaptureService()
        assertNotNull(service)
    }
}
