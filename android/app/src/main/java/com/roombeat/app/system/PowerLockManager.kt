package com.roombeat.app.system

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Abstraction representing an acquired system lock handle.
 */
interface LockHandle {
    val isHeld: Boolean
    fun acquire()
    fun acquire(timeoutMs: Long) { acquire() }
    fun release()
    fun setReferenceCounted(refCounted: Boolean)
}

/**
 * Factory creating system lock handles.
 */
interface LockFactory {
    fun createMulticastLock(tag: String): LockHandle?
    fun createWifiLock(mode: Int, tag: String): LockHandle?
    fun createWakeLock(levelAndFlags: Int, tag: String): LockHandle?
}

class AndroidMulticastLock(private val lock: WifiManager.MulticastLock) : LockHandle {
    override val isHeld: Boolean get() = lock.isHeld
    override fun acquire() = lock.acquire()
    override fun release() {
        if (lock.isHeld) {
            lock.release()
        }
    }
    override fun setReferenceCounted(refCounted: Boolean) = lock.setReferenceCounted(refCounted)
}

class AndroidWifiLock(private val lock: WifiManager.WifiLock) : LockHandle {
    override val isHeld: Boolean get() = lock.isHeld
    override fun acquire() = lock.acquire()
    override fun release() {
        if (lock.isHeld) {
            lock.release()
        }
    }
    override fun setReferenceCounted(refCounted: Boolean) = lock.setReferenceCounted(refCounted)
}

class AndroidWakeLock(private val lock: PowerManager.WakeLock) : LockHandle {
    override val isHeld: Boolean get() = lock.isHeld
    override fun acquire() = lock.acquire()
    override fun acquire(timeoutMs: Long) = lock.acquire(timeoutMs)
    override fun release() {
        if (lock.isHeld) {
            lock.release()
        }
    }
    override fun setReferenceCounted(refCounted: Boolean) = lock.setReferenceCounted(refCounted)
}

class DefaultLockFactory(context: Context) : LockFactory {
    private val appContext = context.applicationContext ?: context
    private val wifiManager: WifiManager? = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager: PowerManager? = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun createMulticastLock(tag: String): LockHandle? {
        return wifiManager?.createMulticastLock(tag)?.let { AndroidMulticastLock(it) }
    }

    override fun createWifiLock(mode: Int, tag: String): LockHandle? {
        return wifiManager?.createWifiLock(mode, tag)?.let { AndroidWifiLock(it) }
    }

    override fun createWakeLock(levelAndFlags: Int, tag: String): LockHandle? {
        return powerManager?.newWakeLock(levelAndFlags, tag)?.let { AndroidWakeLock(it) }
    }
}

/**
 * Diagnostic and audit status report for system power locks.
 */
data class LockStatusReport(
    val isMulticastHeld: Boolean,
    val isWifiHeld: Boolean,
    val isWakeHeld: Boolean,
    val areAllHeld: Boolean,
    val multicastHoldDurationMs: Long,
    val wifiHoldDurationMs: Long,
    val wakeHoldDurationMs: Long,
    val multicastAcquireCount: Long,
    val wifiAcquireCount: Long,
    val wakeAcquireCount: Long,
    val potentialLeaksDetected: Long,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Encapsulates the acquisition, tracking, hardening assertions, and safe release of low-latency
 * network and power locks required for synchronized UDP multicast streaming:
 *
 * 1. [WifiManager.MulticastLock]: Essential for receiving 20ms UDP multicast audio packets
 *    without kernel/Wi-Fi chip packet dropping during power-save states.
 * 2. [WifiManager.WifiLock]: Set to [WifiManager.WIFI_MODE_FULL_LOW_LATENCY] to minimize Wi-Fi
 *    TX/RX jitter and scheduling delays.
 * 3. [PowerManager.WakeLock]: Set to [PowerManager.PARTIAL_WAKE_LOCK] to prevent CPU throttling
 *    or deep sleep while audio streaming is active.
 *
 * Hardened Features for v1.0.1:
 * - Precise acquisition timestamps and cumulative hold duration tracking.
 * - [assertAllLocksHeld] and [assertNoLocksHeld] verification assertions.
 * - Leak detection and automatic release on [close].
 * - Optional safety timeouts on WakeLocks to prevent perpetual battery drain.
 */
class PowerLockManager(
    context: Context? = null,
    lockFactory: LockFactory? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "PowerLockManager"

        const val MULTICAST_LOCK_TAG = "RoomBeat:MulticastLock"
        const val WIFI_LOCK_TAG = "RoomBeat:WifiLock"
        const val WAKE_LOCK_TAG = "RoomBeat:WakeLock"

        /**
         * Default safety timeout (4 hours) applied to wake lock acquisitions to prevent
         * permanent battery depletion in case of abnormal app termination.
         */
        const val DEFAULT_WAKE_LOCK_SAFETY_TIMEOUT_MS = 4 * 60 * 60 * 1000L
    }

    private val lockMutex = Any()
    private val factory: LockFactory? = lockFactory ?: context?.let { DefaultLockFactory(it) }

    val multicastLock: LockHandle? = try {
        factory?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to create MulticastLock: ${e.message}")
        null
    }

    val wifiLock: LockHandle? = try {
        factory?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG)?.apply {
            setReferenceCounted(false)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to create WifiLock: ${e.message}")
        null
    }

    val wakeLock: LockHandle? = try {
        factory?.createWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
            setReferenceCounted(false)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to create WakeLock: ${e.message}")
        null
    }

    // Telemetry & lifecycle metrics
    @Volatile private var multicastAcquireTimeMs: Long = 0L
    @Volatile private var wifiAcquireTimeMs: Long = 0L
    @Volatile private var wakeAcquireTimeMs: Long = 0L

    @Volatile private var totalMulticastHoldDurationMs: Long = 0L
    @Volatile private var totalWifiHoldDurationMs: Long = 0L
    @Volatile private var totalWakeHoldDurationMs: Long = 0L

    @Volatile private var multicastAcquires: Long = 0L
    @Volatile private var wifiAcquires: Long = 0L
    @Volatile private var wakeAcquires: Long = 0L

    @Volatile private var multicastReleases: Long = 0L
    @Volatile private var wifiReleases: Long = 0L
    @Volatile private var wakeReleases: Long = 0L

    @Volatile private var leaksAvertedCount: Long = 0L

    /**
     * Checks whether the MulticastLock is currently held.
     */
    val isMulticastLockHeld: Boolean
        get() = multicastLock?.isHeld == true

    /**
     * Checks whether the low-latency WifiLock is currently held.
     */
    val isWifiLockHeld: Boolean
        get() = wifiLock?.isHeld == true

    /**
     * Checks whether the partial WakeLock is currently held.
     */
    val isWakeLockHeld: Boolean
        get() = wakeLock?.isHeld == true

    /**
     * True if all available locks (Multicast, Low-Latency Wi-Fi, and WakeLock) are actively held.
     */
    val areAllLocksHeld: Boolean
        get() = isMulticastLockHeld && isWifiLockHeld && isWakeLockHeld

    /**
     * Returns true if no locks are currently held (i.e. zero leaks).
     */
    val isCompletelyReleased: Boolean
        get() = !isMulticastLockHeld && !isWifiLockHeld && !isWakeLockHeld

    /**
     * Acquires the MulticastLock if not already held.
     * @return true if successfully acquired or already held, false otherwise.
     */
    fun acquireMulticastLock(): Boolean = synchronized(lockMutex) {
        val lock = multicastLock ?: run {
            Log.w(TAG, "Cannot acquire MulticastLock: lock is null")
            return false
        }
        return try {
            if (!lock.isHeld) {
                lock.acquire()
                multicastAcquireTimeMs = SystemClock.elapsedRealtime()
                multicastAcquires++
                Log.d(TAG, "Acquired MulticastLock [$MULTICAST_LOCK_TAG]")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring MulticastLock: ${e.message}", e)
            false
        }
    }

    /**
     * Releases the MulticastLock safely if held.
     * @return true if released, false if not held or an error occurred.
     */
    fun releaseMulticastLock(): Boolean = synchronized(lockMutex) {
        val lock = multicastLock ?: return false
        return try {
            if (lock.isHeld) {
                lock.release()
                if (multicastAcquireTimeMs > 0) {
                    val heldDuration = SystemClock.elapsedRealtime() - multicastAcquireTimeMs
                    totalMulticastHoldDurationMs += heldDuration
                    multicastAcquireTimeMs = 0L
                    Log.d(TAG, "Released MulticastLock [$MULTICAST_LOCK_TAG] after ${heldDuration}ms")
                } else {
                    Log.d(TAG, "Released MulticastLock [$MULTICAST_LOCK_TAG]")
                }
                multicastReleases++
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MulticastLock: ${e.message}", e)
            false
        }
    }

    /**
     * Acquires the low-latency WifiLock if not already held.
     * @return true if successfully acquired or already held, false otherwise.
     */
    fun acquireWifiLock(): Boolean = synchronized(lockMutex) {
        val lock = wifiLock ?: run {
            Log.w(TAG, "Cannot acquire WifiLock: lock is null")
            return false
        }
        return try {
            if (!lock.isHeld) {
                lock.acquire()
                wifiAcquireTimeMs = SystemClock.elapsedRealtime()
                wifiAcquires++
                Log.d(TAG, "Acquired low-latency WifiLock [$WIFI_LOCK_TAG]")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WifiLock: ${e.message}", e)
            false
        }
    }

    /**
     * Releases the low-latency WifiLock safely if held.
     * @return true if released, false if not held or an error occurred.
     */
    fun releaseWifiLock(): Boolean = synchronized(lockMutex) {
        val lock = wifiLock ?: return false
        return try {
            if (lock.isHeld) {
                lock.release()
                if (wifiAcquireTimeMs > 0) {
                    val heldDuration = SystemClock.elapsedRealtime() - wifiAcquireTimeMs
                    totalWifiHoldDurationMs += heldDuration
                    wifiAcquireTimeMs = 0L
                    Log.d(TAG, "Released WifiLock [$WIFI_LOCK_TAG] after ${heldDuration}ms")
                } else {
                    Log.d(TAG, "Released WifiLock [$WIFI_LOCK_TAG]")
                }
                wifiReleases++
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WifiLock: ${e.message}", e)
            false
        }
    }

    /**
     * Acquires the partial WakeLock if not already held.
     *
     * @param timeoutMs Optional maximum duration in milliseconds before the lock automatically releases.
     * @return true if successfully acquired or already held, false otherwise.
     */
    fun acquireWakeLock(timeoutMs: Long? = null): Boolean = synchronized(lockMutex) {
        val lock = wakeLock ?: run {
            Log.w(TAG, "Cannot acquire WakeLock: lock is null")
            return false
        }
        return try {
            if (!lock.isHeld) {
                if (timeoutMs != null && timeoutMs > 0) {
                    lock.acquire(timeoutMs)
                    Log.d(TAG, "Acquired partial WakeLock [$WAKE_LOCK_TAG] with timeout ${timeoutMs}ms")
                } else {
                    lock.acquire()
                    Log.d(TAG, "Acquired partial WakeLock [$WAKE_LOCK_TAG]")
                }
                wakeAcquireTimeMs = SystemClock.elapsedRealtime()
                wakeAcquires++
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock: ${e.message}", e)
            false
        }
    }

    /**
     * Releases the partial WakeLock safely if held.
     * @return true if released, false if not held or an error occurred.
     */
    fun releaseWakeLock(): Boolean = synchronized(lockMutex) {
        val lock = wakeLock ?: return false
        return try {
            if (lock.isHeld) {
                lock.release()
                if (wakeAcquireTimeMs > 0) {
                    val heldDuration = SystemClock.elapsedRealtime() - wakeAcquireTimeMs
                    totalWakeHoldDurationMs += heldDuration
                    wakeAcquireTimeMs = 0L
                    Log.d(TAG, "Released WakeLock [$WAKE_LOCK_TAG] after ${heldDuration}ms")
                } else {
                    Log.d(TAG, "Released WakeLock [$WAKE_LOCK_TAG]")
                }
                wakeReleases++
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}", e)
            false
        }
    }

    /**
     * Acquires all three locks: MulticastLock, low-latency WifiLock, and partial WakeLock.
     *
     * @param wakeLockTimeoutMs Optional safety timeout applied to the wake lock.
     * @return true if all locks were successfully acquired or already held.
     */
    fun acquireAll(wakeLockTimeoutMs: Long? = null): Boolean = synchronized(lockMutex) {
        val mAcquired = acquireMulticastLock()
        val wAcquired = acquireWifiLock()
        val pAcquired = acquireWakeLock(wakeLockTimeoutMs)
        return mAcquired && wAcquired && pAcquired
    }

    /**
     * Safely releases all locks.
     */
    fun releaseAll(): Unit = synchronized(lockMutex) {
        releaseMulticastLock()
        releaseWifiLock()
        releaseWakeLock()
    }

    /**
     * Asserts that all locks are currently held during an active streaming session.
     *
     * @param callerContext Identifying context string for diagnostics (e.g. "ActiveStreamingSession").
     * @param throwOnError If true, throws an [IllegalStateException] on assertion failure.
     * @return true if all locks are held, false if any lock is missing.
     */
    fun assertAllLocksHeld(callerContext: String = "ActiveSession", throwOnError: Boolean = true): Boolean {
        val allHeld = areAllLocksHeld
        if (!allHeld) {
            val msg = "Lock assertion failed in [$callerContext]: expected all locks held, " +
                    "but state is [Multicast=$isMulticastLockHeld, Wifi=$isWifiLockHeld, Wake=$isWakeLockHeld]"
            Log.e(TAG, msg)
            if (throwOnError) {
                throw IllegalStateException(msg)
            }
        }
        return allHeld
    }

    /**
     * Asserts that all locks have been cleanly released upon session termination.
     *
     * @param callerContext Identifying context string for diagnostics (e.g. "SessionTeardown").
     * @param throwOnError If true, throws an [IllegalStateException] on assertion failure.
     * @return true if all locks are released, false if any lock remains held (leak detected).
     */
    fun assertNoLocksHeld(callerContext: String = "SessionTeardown", throwOnError: Boolean = true): Boolean {
        val noLeaks = isCompletelyReleased
        if (!noLeaks) {
            val msg = "WakeLock/WifiLock leak detected in [$callerContext]! " +
                    "Active locks: [Multicast=$isMulticastLockHeld, Wifi=$isWifiLockHeld, Wake=$isWakeLockHeld]"
            Log.e(TAG, msg)
            if (throwOnError) {
                throw IllegalStateException(msg)
            }
        }
        return noLeaks
    }

    /**
     * Verifies that no locks are leaked.
     * @return true if all locks are released.
     */
    fun verifyNoLeaks(): Boolean = isCompletelyReleased

    /**
     * Returns true if any lock remains held.
     */
    fun hasLeak(): Boolean = !isCompletelyReleased

    /**
     * Produces a comprehensive status and telemetry report for Battery Historian audit.
     */
    fun getStatusReport(): LockStatusReport {
        val now = SystemClock.elapsedRealtime()
        val currentMulticastDuration = totalMulticastHoldDurationMs +
                (if (isMulticastLockHeld && multicastAcquireTimeMs > 0) now - multicastAcquireTimeMs else 0L)
        val currentWifiDuration = totalWifiHoldDurationMs +
                (if (isWifiLockHeld && wifiAcquireTimeMs > 0) now - wifiAcquireTimeMs else 0L)
        val currentWakeDuration = totalWakeHoldDurationMs +
                (if (isWakeLockHeld && wakeAcquireTimeMs > 0) now - wakeAcquireTimeMs else 0L)

        return LockStatusReport(
            isMulticastHeld = isMulticastLockHeld,
            isWifiHeld = isWifiLockHeld,
            isWakeHeld = isWakeLockHeld,
            areAllHeld = areAllLocksHeld,
            multicastHoldDurationMs = currentMulticastDuration,
            wifiHoldDurationMs = currentWifiDuration,
            wakeHoldDurationMs = currentWakeDuration,
            multicastAcquireCount = multicastAcquires,
            wifiAcquireCount = wifiAcquires,
            wakeAcquireCount = wakeAcquires,
            potentialLeaksDetected = leaksAvertedCount
        )
    }

    /**
     * Implements [AutoCloseable] to ensure safe automatic lock release in try-with-resources.
     * Hardened to detect and avert leaks if locks were not explicitly released prior to close.
     */
    override fun close() {
        synchronized(lockMutex) {
            if (!isCompletelyReleased) {
                leaksAvertedCount++
                Log.w(
                    TAG,
                    "PowerLockManager.close() invoked while locks were still actively held! " +
                            "[Multicast=$isMulticastLockHeld, Wifi=$isWifiLockHeld, Wake=$isWakeLockHeld]. " +
                            "Forcibly releasing all locks to prevent system wakelock leak."
                )
            }
            releaseAll()
        }
    }
}
