package com.roombeat.app.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

/**
 * Metadata associated with an incoming or outgoing packet.
 */
data class PacketContext(
    val senderId: String? = null,
    val remoteAddress: String? = null,
    val receivedAtMs: Long = System.currentTimeMillis(),
    val attributes: Map<String, Any> = emptyMap()
)

/**
 * Token handle allowing unregistration of a previously registered packet handler.
 */
fun interface HandlerRegistration {
    fun unregister()
}

/**
 * High-speed, type-safe event router and handler registry for RoomBeat packets.
 * Supports strongly-typed packet listeners, custom coroutine dispatchers, and exception isolation.
 */
class PacketDispatcher(
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val scope: CoroutineScope = CoroutineScope(defaultDispatcher + SupervisorJob())
) {

    private data class RegisteredHandler<T : RoomBeatPacket>(
        val dispatcher: CoroutineDispatcher?,
        val handler: suspend (packet: T, context: PacketContext) -> Unit
    )

    private val typedHandlers = ConcurrentHashMap<KClass<out RoomBeatPacket>, CopyOnWriteArrayList<RegisteredHandler<*>>>()
    private val globalHandlers = CopyOnWriteArrayList<RegisteredHandler<RoomBeatPacket>>()

    /**
     * Optional global callback invoked when an exception is thrown inside a packet handler.
     * Prevents handler crashes from breaking socket read loops.
     */
    var errorHandler: ((Throwable, RoomBeatPacket, PacketContext) -> Unit)? = null

    /**
     * Registers a strongly-typed handler for packets of type [T].
     *
     * @param dispatcher Optional specific dispatcher for this handler. If null, [defaultDispatcher] is used.
     * @param handler Suspending callback invoked when a matching packet is dispatched.
     * @return A [HandlerRegistration] handle to unregister the handler.
     */
    inline fun <reified T : RoomBeatPacket> registerHandler(
        dispatcher: CoroutineDispatcher? = null,
        noinline handler: suspend (packet: T, context: PacketContext) -> Unit
    ): HandlerRegistration {
        return registerHandler(T::class, dispatcher, handler)
    }

    /**
     * Registers a handler for packets of type specified by [packetClass].
     */
    fun <T : RoomBeatPacket> registerHandler(
        packetClass: KClass<T>,
        dispatcher: CoroutineDispatcher? = null,
        handler: suspend (packet: T, context: PacketContext) -> Unit
    ): HandlerRegistration {
        val list = typedHandlers.computeIfAbsent(packetClass) { CopyOnWriteArrayList() }
        val reg = RegisteredHandler(dispatcher, handler)
        list.add(reg)

        return HandlerRegistration {
            list.remove(reg)
            if (list.isEmpty()) {
                typedHandlers.remove(packetClass, list)
            }
        }
    }

    /**
     * Registers a global handler that receives all successfully parsed packets.
     */
    fun registerGlobalHandler(
        dispatcher: CoroutineDispatcher? = null,
        handler: suspend (packet: RoomBeatPacket, context: PacketContext) -> Unit
    ): HandlerRegistration {
        val reg = RegisteredHandler(dispatcher, handler)
        globalHandlers.add(reg)
        return HandlerRegistration {
            globalHandlers.remove(reg)
        }
    }

    /**
     * Unregisters all handlers for a specific packet class.
     */
    fun unregisterAll(packetClass: KClass<out RoomBeatPacket>) {
        typedHandlers.remove(packetClass)
    }

    /**
     * Clears all registered handlers (both typed and global).
     */
    fun unregisterAll() {
        typedHandlers.clear()
        globalHandlers.clear()
    }

    /**
     * Returns the number of handlers registered for a given packet class.
     */
    fun getHandlerCount(packetClass: KClass<out RoomBeatPacket>): Int {
        return (typedHandlers[packetClass]?.size ?: 0) + globalHandlers.size
    }

    /**
     * Dispatches a [packet] to all matching registered handlers sequentially or concurrently.
     * Handler exceptions are isolated and reported via [errorHandler] without throwing to the caller.
     */
    suspend fun dispatch(packet: RoomBeatPacket, context: PacketContext = PacketContext()) {
        val matchingHandlers = typedHandlers[packet::class]?.toList() ?: emptyList()
        val allGlobals = globalHandlers.toList()

        for (untyped in matchingHandlers) {
            @Suppress("UNCHECKED_CAST")
            val reg = untyped as RegisteredHandler<RoomBeatPacket>
            invokeSafely(reg, packet, context)
        }

        for (reg in allGlobals) {
            invokeSafely(reg, packet, context)
        }
    }

    /**
     * Asynchronously launches packet dispatch in the dispatcher's coroutine scope.
     *
     * @return The launched [Job].
     */
    fun dispatchAsync(packet: RoomBeatPacket, context: PacketContext = PacketContext()): Job {
        return scope.launch(defaultDispatcher) {
            dispatch(packet, context)
        }
    }

    /**
     * Deserializes a raw line using [PacketSerializer] and dispatches the resulting packet.
     *
     * @return `true` if the line was valid and dispatched, `false` if malformed or unknown.
     */
    suspend fun dispatchLine(line: String, context: PacketContext = PacketContext()): Boolean {
        val packet = PacketSerializer.deserializeLine(line) ?: return false
        dispatch(packet, context)
        return true
    }

    /**
     * Asynchronously deserializes a raw line and dispatches it in background scope.
     *
     * @return `true` if line was parsed and job launched, `false` if line was malformed.
     */
    fun dispatchLineAsync(line: String, context: PacketContext = PacketContext()): Boolean {
        val packet = PacketSerializer.deserializeLine(line) ?: return false
        dispatchAsync(packet, context)
        return true
    }

    private suspend fun invokeSafely(
        reg: RegisteredHandler<RoomBeatPacket>,
        packet: RoomBeatPacket,
        context: PacketContext
    ) {
        val targetDispatcher = reg.dispatcher ?: defaultDispatcher
        try {
            withContext(targetDispatcher) {
                reg.handler(packet, context)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            errorHandler?.invoke(t, packet, context)
        }
    }
}
