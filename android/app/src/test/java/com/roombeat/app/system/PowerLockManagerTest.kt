package com.roombeat.app.system

import android.net.wifi.WifiManager
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PowerLockManagerTest {

    private class FakeLockHandle : LockHandle {
        override var isHeld: Boolean = false
        var acquireCount: Int = 0
        var releaseCount: Int = 0
        var isReferenceCounted: Boolean? = null

        override fun acquire() {
            acquireCount++
            isHeld = true
        }

        override fun release() {
            releaseCount++
            isHeld = false
        }

        override fun setReferenceCounted(refCounted: Boolean) {
            isReferenceCounted = refCounted
        }
    }

    private class FakeLockFactory : LockFactory {
        var multicastTag: String? = null
        var wifiMode: Int? = null
        var wifiTag: String? = null
        var wakeLevel: Int? = null
        var wakeTag: String? = null

        val fakeMulticastLock = FakeLockHandle()
        val fakeWifiLock = FakeLockHandle()
        val fakeWakeLock = FakeLockHandle()

        var shouldFailMulticast = false
        var shouldFailWifi = false
        var shouldFailWake = false

        override fun createMulticastLock(tag: String): LockHandle? {
            if (shouldFailMulticast) throw IllegalStateException("Simulated multicast failure")
            multicastTag = tag
            return fakeMulticastLock
        }

        override fun createWifiLock(mode: Int, tag: String): LockHandle? {
            if (shouldFailWifi) throw IllegalStateException("Simulated wifi failure")
            wifiMode = mode
            wifiTag = tag
            return fakeWifiLock
        }

        override fun createWakeLock(levelAndFlags: Int, tag: String): LockHandle? {
            if (shouldFailWake) throw IllegalStateException("Simulated wake failure")
            wakeLevel = levelAndFlags
            wakeTag = tag
            return fakeWakeLock
        }
    }

    private lateinit var factory: FakeLockFactory

    @Before
    fun setUp() {
        factory = FakeLockFactory()
    }

    @Test
    fun testLockCreation_CreatesMulticastAndWifiLocksSuccessfully() {
        val manager = PowerLockManager(lockFactory = factory)

        assertNotNull("MulticastLock must be created", manager.multicastLock)
        assertNotNull("WifiLock must be created", manager.wifiLock)
        assertNotNull("WakeLock must be created", manager.wakeLock)

        assertEquals(PowerLockManager.MULTICAST_LOCK_TAG, factory.multicastTag)
        assertEquals(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, factory.wifiMode)
        assertEquals(PowerLockManager.WIFI_LOCK_TAG, factory.wifiTag)
        assertEquals(PowerManager.PARTIAL_WAKE_LOCK, factory.wakeLevel)
        assertEquals(PowerLockManager.WAKE_LOCK_TAG, factory.wakeTag)

        assertEquals(false, factory.fakeMulticastLock.isReferenceCounted)
        assertEquals(false, factory.fakeWifiLock.isReferenceCounted)
        assertEquals(false, factory.fakeWakeLock.isReferenceCounted)
    }

    @Test
    fun testAcquireLocks_SuccessfullyAcquiresMulticastAndWifiLocks() {
        val manager = PowerLockManager(lockFactory = factory)

        assertFalse("Initially multicast lock must not be held", manager.isMulticastLockHeld)
        assertFalse("Initially wifi lock must not be held", manager.isWifiLockHeld)
        assertFalse("Initially wake lock must not be held", manager.isWakeLockHeld)
        assertFalse("Initially not all locks held", manager.areAllLocksHeld)

        val mAcquired = manager.acquireMulticastLock()
        assertTrue("Multicast lock acquire should return true", mAcquired)
        assertTrue("Multicast lock must be held", manager.isMulticastLockHeld)
        assertEquals(1, factory.fakeMulticastLock.acquireCount)

        val wAcquired = manager.acquireWifiLock()
        assertTrue("Wifi lock acquire should return true", wAcquired)
        assertTrue("Wifi lock must be held", manager.isWifiLockHeld)
        assertEquals(1, factory.fakeWifiLock.acquireCount)

        val pAcquired = manager.acquireWakeLock()
        assertTrue("Wake lock acquire should return true", pAcquired)
        assertTrue("Wake lock must be held", manager.isWakeLockHeld)
        assertEquals(1, factory.fakeWakeLock.acquireCount)

        assertTrue("All locks must be held", manager.areAllLocksHeld)
    }

    @Test
    fun testAcquireAll_AcquiresAllThreeLocks() {
        val manager = PowerLockManager(lockFactory = factory)

        val allAcquired = manager.acquireAll()
        assertTrue("acquireAll should succeed", allAcquired)
        assertTrue("Multicast lock must be held", manager.isMulticastLockHeld)
        assertTrue("Wifi lock must be held", manager.isWifiLockHeld)
        assertTrue("Wake lock must be held", manager.isWakeLockHeld)
        assertTrue("areAllLocksHeld must be true", manager.areAllLocksHeld)

        assertEquals(1, factory.fakeMulticastLock.acquireCount)
        assertEquals(1, factory.fakeWifiLock.acquireCount)
        assertEquals(1, factory.fakeWakeLock.acquireCount)
    }

    @Test
    fun testIdempotentAcquire_DoesNotReacquireIfAlreadyHeld() {
        val manager = PowerLockManager(lockFactory = factory)

        manager.acquireMulticastLock()
        manager.acquireMulticastLock()

        assertEquals(1, factory.fakeMulticastLock.acquireCount)
    }

    @Test
    fun testSafeRelease_ReleasesOnlyHeldLocks() {
        val manager = PowerLockManager(lockFactory = factory)

        // Without acquiring first, releasing shouldn't call release() on underlying locks
        val releasedBeforeAcquire = manager.releaseMulticastLock()
        assertFalse("Releasing unheld lock should return false", releasedBeforeAcquire)
        assertEquals(0, factory.fakeMulticastLock.releaseCount)

        // Acquire then release
        manager.acquireMulticastLock()
        val releasedAfterAcquire = manager.releaseMulticastLock()
        assertTrue("Releasing held lock should return true", releasedAfterAcquire)
        assertEquals(1, factory.fakeMulticastLock.releaseCount)
        assertFalse("Multicast lock should no longer be held", manager.isMulticastLockHeld)
    }

    @Test
    fun testReleaseAllAndClose_ReleasesAllHeldLocks() {
        val manager = PowerLockManager(lockFactory = factory)

        manager.acquireAll()
        assertTrue(manager.areAllLocksHeld)

        manager.releaseAll()
        assertFalse(manager.isMulticastLockHeld)
        assertFalse(manager.isWifiLockHeld)
        assertFalse(manager.isWakeLockHeld)

        assertEquals(1, factory.fakeMulticastLock.releaseCount)
        assertEquals(1, factory.fakeWifiLock.releaseCount)
        assertEquals(1, factory.fakeWakeLock.releaseCount)
    }

    @Test
    fun testAutoCloseable_ReleasesLocksOnClose() {
        PowerLockManager(lockFactory = factory).use { manager ->
            manager.acquireAll()
            assertTrue(manager.areAllLocksHeld)
        }

        assertEquals(1, factory.fakeMulticastLock.releaseCount)
        assertEquals(1, factory.fakeWifiLock.releaseCount)
        assertEquals(1, factory.fakeWakeLock.releaseCount)
    }

    @Test
    fun testFailureHandling_NullLocksDoNotCrash() {
        factory.shouldFailMulticast = true
        val manager = PowerLockManager(lockFactory = factory)

        assertNull("MulticastLock should be null when creation fails", manager.multicastLock)
        assertFalse("Acquiring null multicast lock should return false", manager.acquireMulticastLock())
        assertFalse("Releasing null multicast lock should return false", manager.releaseMulticastLock())
        assertFalse("areAllLocksHeld should be false when multicast lock is missing", manager.areAllLocksHeld)
    }
}
