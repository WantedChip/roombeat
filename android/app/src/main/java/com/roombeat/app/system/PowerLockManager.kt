package com.roombeat.app.system

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Abstraction representing an acquired system lock handle.
 */
interface LockHandle {
    val isHeld: Boolean
    fun acquire()
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
    override fun release() = lock.release()
    override fun setReferenceCounted(refCounted: Boolean) = lock.setReferenceCounted(refCounted)
}

class AndroidWifiLock(private val lock: WifiManager.WifiLock) : LockHandle {
    override val isHeld: Boolean get() = lock.isHeld
    override fun acquire() = lock.acquire()
    override fun release() = lock.release()
    override fun setReferenceCounted(refCounted: Boolean) = lock.setReferenceCounted(refCounted)
}

class AndroidWakeLock(private val lock: PowerManager.WakeLock) : LockHandle {
    override val isHeld: Boolean get() = lock.isHeld
    override fun acquire() = lock.acquire()
    override fun release() = lock.release()
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
 * Encapsulates the acquisition, tracking, and safe release of low-latency network
 * and power locks required for synchronized UDP multicast streaming:
 *
 * 1. [WifiManager.MulticastLock]: Essential for receiving 20ms UDP multicast audio packets
 *    without kernel/Wi-Fi chip packet dropping during power-save states.
 * 2. [WifiManager.WifiLock]: Set to [WifiManager.WIFI_MODE_FULL_LOW_LATENCY] to minimize Wi-Fi
 *    TX/RX jitter and scheduling delays.
 * 3. [PowerManager.WakeLock]: Set to [PowerManager.PARTIAL_WAKE_LOCK] to prevent CPU throttling
 *    or deep sleep while audio streaming is active.
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
    }

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
     * Acquires the MulticastLock if not already held.
     * @return true if successfully acquired or already held, false otherwise.
     */
    fun acquireMulticastLock(): Boolean {
        val lock = multicastLock ?: run {
            Log.w(TAG, "Cannot acquire MulticastLock: lock is null")
            return false
        }
        return try {
            if (!lock.isHeld) {
                lock.acquire()
                Log.d(TAG, "Acquired MulticastLock")
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
    fun releaseMulticastLock(): Boolean {
        val lock = multicastLock ?: return false
        return try {
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "Released MulticastLock")
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
    fun acquireWifiLock(): Boolean {
        val lock = wifiLock ?: run {
            Log.w(TAG, "Cannot acquire WifiLock: lock is null")
            return false
        }
        return try {
            if (!lock.isHeld) {
                lock.acquire()
                Log.d(TAG, "Acquired low-latency WifiLock")
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
    fun releaseWifiLock(): Boolean {
        val lock = wifiLock ?: return false
        return try {
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "Released WifiLock")
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
     * @return true if successfully acquired or already held, false otherwise.
     */
    fun acquireWakeLock(): Boolean {
        val lock = wakeLock ?: run {
            Log.w(TAG, "Cannot acquire WakeLock: lock is null")
            return false
        }
        return try {
            if (!lock.isHeld) {
                lock.acquire()
                Log.d(TAG, "Acquired partial WakeLock")
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
    fun releaseWakeLock(): Boolean {
        val lock = wakeLock ?: return false
        return try {
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "Released partial WakeLock")
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
     * @return true if all locks were successfully acquired or already held.
     */
    fun acquireAll(): Boolean {
        val mAcquired = acquireMulticastLock()
        val wAcquired = acquireWifiLock()
        val pAcquired = acquireWakeLock()
        return mAcquired && wAcquired && pAcquired
    }

    /**
     * Safely releases all locks.
     */
    fun releaseAll() {
        releaseMulticastLock()
        releaseWifiLock()
        releaseWakeLock()
    }

    /**
     * Implements [AutoCloseable] to ensure safe automatic lock release in try-with-resources.
     */
    override fun close() {
        releaseAll()
    }
}
