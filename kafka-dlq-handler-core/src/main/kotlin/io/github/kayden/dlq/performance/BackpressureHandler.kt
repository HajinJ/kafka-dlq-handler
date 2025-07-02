package io.github.kayden.dlq.performance

import io.github.kayden.dlq.core.model.DLQRecord
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Strategy pattern for backpressure handling
 */
interface BackpressureStrategy {
    fun shouldAccept(currentLoad: Long, maxCapacity: Long): Boolean
    fun onAccepted()
    fun onRejected()
    fun name(): String
}

/**
 * Configuration for backpressure handling
 */
data class BackpressureConfig(
    val maxPending: Long = 100_000L,
    val adaptiveThreshold: Double = 0.85,
    val adaptiveCheckInterval: Duration = 100.milliseconds,
    val initialStrategy: BackpressureStrategyType = BackpressureStrategyType.ADAPTIVE
) {
    init {
        require(maxPending > 0) { "maxPending must be positive" }
        require(adaptiveThreshold in 0.0..1.0) { "adaptiveThreshold must be between 0 and 1" }
    }
}

/**
 * Backpressure strategy types
 */
enum class BackpressureStrategyType {
    DROP_OLDEST,
    DROP_NEWEST,
    BLOCK,
    ADAPTIVE
}

/**
 * Main interface for backpressure handling
 */
interface BackpressureHandler {
    fun shouldAccept(): Boolean
    fun onAccepted()
    fun onRejected()
    fun currentStrategy(): BackpressureStrategyType
    fun getMetrics(): BackpressureMetrics
}

/**
 * Metrics for backpressure monitoring
 */
data class BackpressureMetrics(
    val accepted: Long,
    val rejected: Long,
    val dropped: Long,
    val currentPending: Long,
    val utilization: Double,
    val currentStrategy: String
)

/**
 * Adaptive backpressure handler implementation
 */
class AdaptiveBackpressureHandler(
    private val config: BackpressureConfig,
    private val pendingCounter: () -> Long
) : BackpressureHandler {
    
    private val acceptedCount = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)
    private val currentStrategyRef = AtomicReference(config.initialStrategy)
    private val lastAdaptiveCheck = AtomicLong(System.currentTimeMillis())
    
    private val strategies: Map<BackpressureStrategyType, BackpressureStrategy> = mapOf(
        BackpressureStrategyType.DROP_OLDEST to DropOldestStrategy(),
        BackpressureStrategyType.DROP_NEWEST to DropNewestStrategy(),
        BackpressureStrategyType.BLOCK to BlockStrategy(),
        BackpressureStrategyType.ADAPTIVE to AdaptiveStrategy()
    )
    
    override fun shouldAccept(): Boolean {
        val pending = pendingCounter()
        val strategy = strategies[currentStrategyRef.get()] ?: strategies[BackpressureStrategyType.BLOCK]!!
        
        return strategy.shouldAccept(pending, config.maxPending)
    }
    
    override fun onAccepted() {
        acceptedCount.incrementAndGet()
        strategies[currentStrategyRef.get()]?.onAccepted()
    }
    
    override fun onRejected() {
        rejectedCount.incrementAndGet()
        strategies[currentStrategyRef.get()]?.onRejected()
    }
    
    override fun currentStrategy(): BackpressureStrategyType = currentStrategyRef.get()
    
    override fun getMetrics(): BackpressureMetrics {
        val pending = pendingCounter()
        return BackpressureMetrics(
            accepted = acceptedCount.get(),
            rejected = rejectedCount.get(),
            dropped = droppedCount.get(),
            currentPending = pending,
            utilization = pending.toDouble() / config.maxPending,
            currentStrategy = currentStrategyRef.get().name
        )
    }
    
    private inner class DropOldestStrategy : BackpressureStrategy {
        override fun shouldAccept(currentLoad: Long, maxCapacity: Long): Boolean {
            if (currentLoad >= maxCapacity) {
                droppedCount.incrementAndGet()
                // In real implementation, would trigger drop of oldest message
                return true
            }
            return true
        }
        
        override fun onAccepted() {}
        override fun onRejected() {}
        override fun name(): String = "DROP_OLDEST"
    }
    
    private inner class DropNewestStrategy : BackpressureStrategy {
        override fun shouldAccept(currentLoad: Long, maxCapacity: Long): Boolean {
            return currentLoad < maxCapacity
        }
        
        override fun onAccepted() {}
        override fun onRejected() {
            droppedCount.incrementAndGet()
        }
        override fun name(): String = "DROP_NEWEST"
    }
    
    private inner class BlockStrategy : BackpressureStrategy {
        override fun shouldAccept(currentLoad: Long, maxCapacity: Long): Boolean {
            while (currentLoad >= maxCapacity) {
                Thread.yield()
                val newLoad = pendingCounter()
                if (newLoad < maxCapacity) return true
            }
            return true
        }
        
        override fun onAccepted() {}
        override fun onRejected() {}
        override fun name(): String = "BLOCK"
    }
    
    private inner class AdaptiveStrategy : BackpressureStrategy {
        override fun shouldAccept(currentLoad: Long, maxCapacity: Long): Boolean {
            // Check if we need to adapt strategy
            val now = System.currentTimeMillis()
            val lastCheck = lastAdaptiveCheck.get()
            
            if (now - lastCheck > config.adaptiveCheckInterval.inWholeMilliseconds) {
                if (lastAdaptiveCheck.compareAndSet(lastCheck, now)) {
                    adaptStrategy(currentLoad, maxCapacity)
                }
            }
            
            // Use current strategy
            val currentStrategy = when (currentStrategyRef.get()) {
                BackpressureStrategyType.ADAPTIVE -> BackpressureStrategyType.BLOCK
                else -> currentStrategyRef.get()
            }
            
            return strategies[currentStrategy]?.shouldAccept(currentLoad, maxCapacity) ?: true
        }
        
        private fun adaptStrategy(currentLoad: Long, maxCapacity: Long) {
            val utilization = currentLoad.toDouble() / maxCapacity
            
            val newStrategy = when {
                utilization > 0.95 -> BackpressureStrategyType.DROP_NEWEST
                utilization > config.adaptiveThreshold -> BackpressureStrategyType.DROP_OLDEST
                else -> BackpressureStrategyType.BLOCK
            }
            
            if (currentStrategyRef.get() != newStrategy) {
                currentStrategyRef.set(newStrategy)
            }
        }
        
        override fun onAccepted() {}
        override fun onRejected() {}
        override fun name(): String = "ADAPTIVE"
    }
}

/**
 * Factory for creating BackpressureHandler instances
 */
object BackpressureHandlerFactory {
    fun create(
        config: BackpressureConfig = BackpressureConfig(),
        pendingCounter: () -> Long
    ): BackpressureHandler {
        return AdaptiveBackpressureHandler(config, pendingCounter)
    }
}