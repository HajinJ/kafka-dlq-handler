package io.github.kayden.dlq.core.performance

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Adaptive backpressure strategy that adjusts acceptance rate based on system performance.
 * 
 * This strategy monitors system metrics and dynamically adjusts the acceptance rate
 * to maintain optimal throughput while preventing system overload.
 * 
 * @property config Backpressure configuration
 * @since 0.1.0
 */
class AdaptiveBackpressureStrategy(
    private val config: BackpressureConfig = BackpressureConfig.DEFAULT
) : BackpressureStrategy {
    
    private val acceptedCount = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)
    private val completedCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    
    private val processingTimes = ConcurrentLinkedDeque<ProcessingTimeSample>()
    private val throughputSamples = ConcurrentLinkedDeque<ThroughputSample>()
    
    @Volatile
    private var currentAcceptanceRate = 1.0
    
    @Volatile
    private var lastAdjustmentTime = Instant.now()
    private val adjustmentInterval = Duration.ofSeconds(5)
    
    private var lastResetTime = Instant.now()
    
    override fun shouldAccept(currentLoad: LoadMetrics): Boolean {
        cleanupOldSamples()
        
        // Critical load - reject immediately
        if (currentLoad.isCriticalLoad()) {
            return false
        }
        
        // Check hard limits
        if (currentLoad.pendingMessages > config.maxPendingMessages ||
            currentLoad.memoryUsagePercent > config.maxMemoryUsage ||
            currentLoad.cpuUsagePercent > config.maxCpuUsage ||
            currentLoad.errorRate > config.maxErrorRate) {
            adjustAcceptanceRate(currentLoad, decrease = true)
            return false
        }
        
        // Apply adaptive acceptance rate
        val shouldAccept = Math.random() < currentAcceptanceRate
        
        // Adjust rate periodically
        if (Duration.between(lastAdjustmentTime, Instant.now()) > adjustmentInterval) {
            adjustAcceptanceRate(currentLoad, decrease = false)
            lastAdjustmentTime = Instant.now()
        }
        
        return shouldAccept
    }
    
    override fun onAccepted() {
        acceptedCount.incrementAndGet()
        recordThroughputSample()
    }
    
    override fun onRejected() {
        rejectedCount.incrementAndGet()
        recordThroughputSample()
    }
    
    override fun onCompleted(success: Boolean, duration: Duration) {
        completedCount.incrementAndGet()
        if (!success) {
            errorCount.incrementAndGet()
        }
        
        processingTimes.offer(ProcessingTimeSample(Instant.now(), duration))
        
        // Keep only recent samples
        while (processingTimes.size > 1000) {
            processingTimes.poll()
        }
    }
    
    override fun getMetrics(): BackpressureMetrics {
        val currentThroughput = calculateCurrentThroughput()
        val totalProcessed = acceptedCount.get() + rejectedCount.get()
        val acceptanceRate = if (totalProcessed > 0) {
            acceptedCount.get().toDouble() / totalProcessed
        } else {
            1.0
        }
        
        return BackpressureMetrics(
            totalAccepted = acceptedCount.get(),
            totalRejected = rejectedCount.get(),
            currentThroughput = currentThroughput,
            acceptanceRate = acceptanceRate,
            lastResetTime = lastResetTime
        )
    }
    
    override fun reset() {
        acceptedCount.set(0)
        rejectedCount.set(0)
        completedCount.set(0)
        errorCount.set(0)
        processingTimes.clear()
        throughputSamples.clear()
        currentAcceptanceRate = 1.0
        lastAdjustmentTime = Instant.now()
        lastResetTime = Instant.now()
    }
    
    private fun adjustAcceptanceRate(load: LoadMetrics, decrease: Boolean) {
        val currentErrorRate = calculateRecentErrorRate()
        val avgProcessingTime = calculateAverageProcessingTime()
        
        when {
            // System is overloaded - decrease rate
            decrease || load.isHighLoad() || currentErrorRate > config.maxErrorRate -> {
                currentAcceptanceRate = max(0.1, currentAcceptanceRate * 0.8)
            }
            
            // System has capacity - increase rate
            load.pendingMessages < config.maxPendingMessages * 0.5 &&
            load.memoryUsagePercent < config.maxMemoryUsage * 0.7 &&
            load.cpuUsagePercent < config.maxCpuUsage * 0.7 &&
            currentErrorRate < config.maxErrorRate * 0.5 -> {
                currentAcceptanceRate = min(1.0, currentAcceptanceRate * 1.1)
            }
            
            // Processing is getting slower - decrease rate slightly
            avgProcessingTime > load.averageProcessingTime.multipliedBy(2) -> {
                currentAcceptanceRate = max(0.2, currentAcceptanceRate * 0.9)
            }
        }
    }
    
    private fun calculateRecentErrorRate(): Double {
        val recentCompleted = completedCount.get()
        val recentErrors = errorCount.get()
        
        return if (recentCompleted > 0) {
            recentErrors.toDouble() / recentCompleted
        } else {
            0.0
        }
    }
    
    private fun calculateAverageProcessingTime(): Duration {
        val recentSamples = processingTimes.filter { sample ->
            Duration.between(sample.timestamp, Instant.now()) < config.evaluationWindow
        }
        
        return if (recentSamples.isNotEmpty()) {
            val avgMillis = recentSamples.map { it.duration.toMillis() }.average()
            Duration.ofMillis(avgMillis.toLong())
        } else {
            Duration.ZERO
        }
    }
    
    private fun calculateCurrentThroughput(): Double {
        val recentSamples = throughputSamples.filter { sample ->
            Duration.between(sample.timestamp, Instant.now()) < Duration.ofSeconds(10)
        }
        
        return if (recentSamples.size >= 2) {
            val first = recentSamples.first()
            val last = recentSamples.last()
            val messages = last.totalMessages - first.totalMessages
            val seconds = Duration.between(first.timestamp, last.timestamp).toMillis() / 1000.0
            if (seconds > 0) messages / seconds else 0.0
        } else {
            0.0
        }
    }
    
    private fun recordThroughputSample() {
        val sample = ThroughputSample(
            timestamp = Instant.now(),
            totalMessages = acceptedCount.get() + rejectedCount.get()
        )
        throughputSamples.offer(sample)
    }
    
    private fun cleanupOldSamples() {
        val cutoff = Instant.now().minus(config.evaluationWindow)
        
        throughputSamples.removeIf { it.timestamp < cutoff }
        processingTimes.removeIf { it.timestamp < cutoff }
    }
    
    private data class ProcessingTimeSample(
        val timestamp: Instant,
        val duration: Duration
    )
    
    private data class ThroughputSample(
        val timestamp: Instant,
        val totalMessages: Long
    )
}

/**
 * Simple threshold-based backpressure strategy.
 * 
 * Accepts messages when all metrics are below configured thresholds.
 * 
 * @property config Backpressure configuration
 * @since 0.1.0
 */
class ThresholdBackpressureStrategy(
    private val config: BackpressureConfig = BackpressureConfig.DEFAULT
) : BackpressureStrategy {
    
    private val acceptedCount = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)
    private var lastResetTime = Instant.now()
    
    override fun shouldAccept(currentLoad: LoadMetrics): Boolean {
        return currentLoad.pendingMessages <= config.maxPendingMessages &&
               currentLoad.memoryUsagePercent <= config.maxMemoryUsage &&
               currentLoad.cpuUsagePercent <= config.maxCpuUsage &&
               currentLoad.errorRate <= config.maxErrorRate
    }
    
    override fun onAccepted() {
        acceptedCount.incrementAndGet()
    }
    
    override fun onRejected() {
        rejectedCount.incrementAndGet()
    }
    
    override fun onCompleted(success: Boolean, duration: Duration) {
        // No-op for simple strategy
    }
    
    override fun getMetrics(): BackpressureMetrics {
        val total = acceptedCount.get() + rejectedCount.get()
        return BackpressureMetrics(
            totalAccepted = acceptedCount.get(),
            totalRejected = rejectedCount.get(),
            currentThroughput = 0.0, // Not tracked in simple strategy
            acceptanceRate = if (total > 0) acceptedCount.get().toDouble() / total else 1.0,
            lastResetTime = lastResetTime
        )
    }
    
    override fun reset() {
        acceptedCount.set(0)
        rejectedCount.set(0)
        lastResetTime = Instant.now()
    }
}