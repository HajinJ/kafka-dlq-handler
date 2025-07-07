package io.github.kayden.dlq.core.performance

import java.time.Duration
import java.time.Instant

/**
 * Strategy for handling backpressure in message processing.
 * 
 * Backpressure occurs when producers generate messages faster than consumers
 * can process them. This interface defines different strategies to handle
 * such situations.
 * 
 * @since 0.1.0
 */
interface BackpressureStrategy {
    
    /**
     * Determines whether to accept a new message based on current load.
     * 
     * @param currentLoad Current system load metrics
     * @return true if the message should be accepted, false otherwise
     */
    fun shouldAccept(currentLoad: LoadMetrics): Boolean
    
    /**
     * Called when a message is accepted for processing.
     * 
     * Allows the strategy to update its internal state.
     */
    fun onAccepted()
    
    /**
     * Called when a message is rejected due to backpressure.
     * 
     * Allows the strategy to update its internal state.
     */
    fun onRejected()
    
    /**
     * Called when a message processing is completed.
     * 
     * @param success Whether the processing was successful
     * @param duration Time taken to process the message
     */
    fun onCompleted(success: Boolean, duration: Duration)
    
    /**
     * Gets the current strategy metrics.
     * 
     * @return Current metrics snapshot
     */
    fun getMetrics(): BackpressureMetrics
    
    /**
     * Resets the strategy state.
     */
    fun reset()
}

/**
 * System load metrics used for backpressure decisions.
 * 
 * @property pendingMessages Number of messages waiting to be processed
 * @property processingMessages Number of messages currently being processed
 * @property memoryUsagePercent Current memory usage as a percentage (0-100)
 * @property cpuUsagePercent Current CPU usage as a percentage (0-100)
 * @property errorRate Recent error rate (0-1)
 * @property averageProcessingTime Average time to process a message
 * @since 0.1.0
 */
data class LoadMetrics(
    val pendingMessages: Long,
    val processingMessages: Long,
    val memoryUsagePercent: Double,
    val cpuUsagePercent: Double,
    val errorRate: Double,
    val averageProcessingTime: Duration
) {
    /**
     * Total number of messages in the system.
     */
    val totalMessages: Long = pendingMessages + processingMessages
    
    /**
     * Whether the system is under high load.
     */
    fun isHighLoad(): Boolean {
        return memoryUsagePercent > 80 || 
               cpuUsagePercent > 80 || 
               errorRate > 0.1 ||
               pendingMessages > 10000
    }
    
    /**
     * Whether the system is under critical load.
     */
    fun isCriticalLoad(): Boolean {
        return memoryUsagePercent > 95 || 
               cpuUsagePercent > 95 || 
               errorRate > 0.5 ||
               pendingMessages > 50000
    }
}

/**
 * Metrics for backpressure strategy performance.
 * 
 * @property totalAccepted Total number of messages accepted
 * @property totalRejected Total number of messages rejected
 * @property currentThroughput Current throughput (messages/second)
 * @property acceptanceRate Current acceptance rate (0-1)
 * @property lastResetTime Time when metrics were last reset
 * @since 0.1.0
 */
data class BackpressureMetrics(
    val totalAccepted: Long,
    val totalRejected: Long,
    val currentThroughput: Double,
    val acceptanceRate: Double,
    val lastResetTime: Instant
) {
    /**
     * Total number of messages processed.
     */
    val totalProcessed: Long = totalAccepted + totalRejected
    
    /**
     * Rejection rate as a percentage.
     */
    val rejectionRate: Double = if (totalProcessed > 0) {
        (totalRejected.toDouble() / totalProcessed) * 100
    } else {
        0.0
    }
}

/**
 * Configuration for backpressure strategies.
 * 
 * @property maxPendingMessages Maximum number of pending messages
 * @property maxMemoryUsage Maximum memory usage percentage (0-100)
 * @property maxCpuUsage Maximum CPU usage percentage (0-100)
 * @property maxErrorRate Maximum acceptable error rate (0-1)
 * @property evaluationWindow Time window for rate calculations
 * @since 0.1.0
 */
data class BackpressureConfig(
    val maxPendingMessages: Long = 10000,
    val maxMemoryUsage: Double = 85.0,
    val maxCpuUsage: Double = 85.0,
    val maxErrorRate: Double = 0.05,
    val evaluationWindow: Duration = Duration.ofMinutes(1)
) {
    init {
        require(maxPendingMessages > 0) { "maxPendingMessages must be positive" }
        require(maxMemoryUsage in 0.0..100.0) { "maxMemoryUsage must be between 0 and 100" }
        require(maxCpuUsage in 0.0..100.0) { "maxCpuUsage must be between 0 and 100" }
        require(maxErrorRate in 0.0..1.0) { "maxErrorRate must be between 0 and 1" }
        require(!evaluationWindow.isNegative) { "evaluationWindow must not be negative" }
    }
    
    companion object {
        /**
         * Default configuration with moderate limits.
         */
        val DEFAULT = BackpressureConfig()
        
        /**
         * Aggressive configuration with tight limits.
         */
        val AGGRESSIVE = BackpressureConfig(
            maxPendingMessages = 1000,
            maxMemoryUsage = 70.0,
            maxCpuUsage = 70.0,
            maxErrorRate = 0.01,
            evaluationWindow = Duration.ofSeconds(30)
        )
        
        /**
         * Relaxed configuration with loose limits.
         */
        val RELAXED = BackpressureConfig(
            maxPendingMessages = 50000,
            maxMemoryUsage = 95.0,
            maxCpuUsage = 95.0,
            maxErrorRate = 0.2,
            evaluationWindow = Duration.ofMinutes(5)
        )
    }
}