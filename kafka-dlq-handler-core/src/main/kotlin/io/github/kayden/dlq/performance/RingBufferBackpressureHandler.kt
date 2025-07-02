package io.github.kayden.dlq.performance

import io.github.kayden.dlq.core.model.DLQRecord
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * BackpressureHandler implementation integrated with RingBuffer
 * 
 * This handler works directly with RingBuffer to provide efficient
 * backpressure management based on buffer utilization.
 */
class RingBufferBackpressureHandler(
    private val ringBuffer: RingBuffer,
    private val config: BackpressureConfig = BackpressureConfig()
) : BackpressureHandler {
    
    private val acceptedCount = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)
    private val currentStrategyRef = AtomicReference(config.initialStrategy)
    private val lastAdaptiveCheck = AtomicLong(System.currentTimeMillis())
    
    override fun shouldAccept(): Boolean {
        val utilization = ringBuffer.utilization()
        val currentSize = ringBuffer.size()
        val capacity = ringBuffer.capacity()
        
        return when (currentStrategyRef.get()) {
            BackpressureStrategyType.DROP_OLDEST -> handleDropOldest(currentSize, capacity)
            BackpressureStrategyType.DROP_NEWEST -> handleDropNewest(currentSize, capacity)
            BackpressureStrategyType.BLOCK -> handleBlock(currentSize, capacity)
            BackpressureStrategyType.ADAPTIVE -> handleAdaptive(utilization, currentSize, capacity)
        }
    }
    
    override fun onAccepted() {
        acceptedCount.incrementAndGet()
    }
    
    override fun onRejected() {
        rejectedCount.incrementAndGet()
    }
    
    override fun currentStrategy(): BackpressureStrategyType = currentStrategyRef.get()
    
    override fun getMetrics(): BackpressureMetrics {
        return BackpressureMetrics(
            accepted = acceptedCount.get(),
            rejected = rejectedCount.get(),
            dropped = droppedCount.get(),
            currentPending = ringBuffer.size().toLong(),
            utilization = ringBuffer.utilization() / 100.0,
            currentStrategy = currentStrategyRef.get().name
        )
    }
    
    private fun handleDropOldest(currentSize: Int, capacity: Int): Boolean {
        if (currentSize >= capacity) {
            // Drop the oldest message by polling it out
            val dropped = ringBuffer.poll()
            if (dropped != null) {
                droppedCount.incrementAndGet()
            }
        }
        return true // Always accept after potentially dropping oldest
    }
    
    private fun handleDropNewest(currentSize: Int, capacity: Int): Boolean {
        // Reject new messages when at capacity
        return currentSize < capacity
    }
    
    private fun handleBlock(currentSize: Int, capacity: Int): Boolean {
        // Spin-wait until space is available
        var attempts = 0
        while (currentSize >= capacity) {
            Thread.yield()
            
            // Prevent infinite blocking with timeout
            if (++attempts > 1000) {
                return false
            }
            
            if (ringBuffer.size() < capacity) {
                return true
            }
        }
        return true
    }
    
    private fun handleAdaptive(utilization: Double, currentSize: Int, capacity: Int): Boolean {
        // Check if we need to adapt strategy
        val now = System.currentTimeMillis()
        val lastCheck = lastAdaptiveCheck.get()
        
        if (now - lastCheck > config.adaptiveCheckInterval.inWholeMilliseconds) {
            if (lastAdaptiveCheck.compareAndSet(lastCheck, now)) {
                adaptStrategy(utilization)
            }
        }
        
        // Apply current strategy
        return when (currentStrategyRef.get()) {
            BackpressureStrategyType.ADAPTIVE -> {
                // Use the adapted strategy
                val effectiveStrategy = determineEffectiveStrategy(utilization)
                when (effectiveStrategy) {
                    BackpressureStrategyType.DROP_OLDEST -> handleDropOldest(currentSize, capacity)
                    BackpressureStrategyType.DROP_NEWEST -> handleDropNewest(currentSize, capacity)
                    else -> handleBlock(currentSize, capacity)
                }
            }
            else -> shouldAccept() // Recursive call with the new strategy
        }
    }
    
    private fun adaptStrategy(utilization: Double) {
        val newStrategy = determineEffectiveStrategy(utilization)
        
        // Only update if strategy actually changes
        if (currentStrategyRef.get() != BackpressureStrategyType.ADAPTIVE) {
            return
        }
        
        // Log strategy changes for monitoring
        val current = currentStrategyRef.get()
        if (current != newStrategy && newStrategy != BackpressureStrategyType.ADAPTIVE) {
            // In production, this would be logged
            // logger.info("Adapting backpressure strategy from $current to $newStrategy (utilization: $utilization)")
        }
    }
    
    private fun determineEffectiveStrategy(utilization: Double): BackpressureStrategyType {
        // utilization is in percentage (0-100), convert to ratio for comparison
        val utilizationRatio = utilization / 100.0
        return when {
            utilizationRatio > 0.95 -> BackpressureStrategyType.DROP_NEWEST
            utilizationRatio > config.adaptiveThreshold -> BackpressureStrategyType.DROP_OLDEST
            else -> BackpressureStrategyType.BLOCK
        }
    }
}

/**
 * Extension function to create a BackpressureHandler for a RingBuffer
 */
fun RingBuffer.withBackpressure(
    config: BackpressureConfig = BackpressureConfig()
): Pair<RingBuffer, BackpressureHandler> {
    val handler = RingBufferBackpressureHandler(this, config)
    return this to handler
}

/**
 * Backpressure-aware RingBuffer wrapper
 */
class BackpressureAwareRingBuffer(
    private val delegate: RingBuffer,
    private val backpressureHandler: BackpressureHandler
) {
    
    fun offer(item: DLQRecord): Boolean {
        if (!backpressureHandler.shouldAccept()) {
            backpressureHandler.onRejected()
            return false
        }
        
        val success = delegate.offer(item)
        if (success) {
            backpressureHandler.onAccepted()
        } else {
            backpressureHandler.onRejected()
        }
        
        return success
    }
    
    fun poll(): DLQRecord? = delegate.poll()
    
    fun size(): Int = delegate.size()
    
    fun capacity(): Int = delegate.capacity()
    
    fun utilization(): Double = delegate.utilization()
    
    fun isEmpty(): Boolean = delegate.isEmpty()
    
    fun metrics(): BackpressureMetrics = backpressureHandler.getMetrics()
}