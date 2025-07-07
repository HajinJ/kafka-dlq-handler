package io.github.kayden.dlq.core.performance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import java.time.Duration

class AdaptiveBackpressureStrategyTest : FunSpec({
    
    test("should accept messages when system load is low") {
        val strategy = AdaptiveBackpressureStrategy()
        val lowLoad = LoadMetrics(
            pendingMessages = 100,
            processingMessages = 10,
            memoryUsagePercent = 30.0,
            cpuUsagePercent = 25.0,
            errorRate = 0.01,
            averageProcessingTime = Duration.ofMillis(10)
        )
        
        var accepted = 0
        repeat(100) {
            if (strategy.shouldAccept(lowLoad)) {
                strategy.onAccepted()
                accepted++
            }
        }
        
        accepted shouldBeGreaterThan 90.toLong() // Should accept most messages
    }
    
    test("should reject messages when system load is critical") {
        val strategy = AdaptiveBackpressureStrategy()
        val criticalLoad = LoadMetrics(
            pendingMessages = 60000,
            processingMessages = 5000,
            memoryUsagePercent = 96.0,
            cpuUsagePercent = 98.0,
            errorRate = 0.6,
            averageProcessingTime = Duration.ofSeconds(2)
        )
        
        var rejected = 0
        repeat(100) {
            if (!strategy.shouldAccept(criticalLoad)) {
                strategy.onRejected()
                rejected++
            }
        }
        
        rejected shouldBe 100 // Should reject all messages
    }
    
    test("should adapt acceptance rate based on system performance") {
        val config = BackpressureConfig(
            maxPendingMessages = 1000,
            maxMemoryUsage = 80.0,
            maxCpuUsage = 80.0,
            maxErrorRate = 0.1
        )
        val strategy = AdaptiveBackpressureStrategy(config)
        
        // Start with moderate load
        val moderateLoad = LoadMetrics(
            pendingMessages = 500,
            processingMessages = 50,
            memoryUsagePercent = 60.0,
            cpuUsagePercent = 60.0,
            errorRate = 0.05,
            averageProcessingTime = Duration.ofMillis(20)
        )
        
        // Accept some messages
        repeat(100) {
            if (strategy.shouldAccept(moderateLoad)) {
                strategy.onAccepted()
                strategy.onCompleted(true, Duration.ofMillis(20))
            }
        }
        
        val initialMetrics = strategy.getMetrics()
        initialMetrics.acceptanceRate shouldBeGreaterThanOrEqual 0.5
        
        // Simulate high load
        val highLoad = LoadMetrics(
            pendingMessages = 900,
            processingMessages = 100,
            memoryUsagePercent = 85.0,
            cpuUsagePercent = 85.0,
            errorRate = 0.15,
            averageProcessingTime = Duration.ofMillis(50)
        )
        
        // Force adjustment by waiting
        Thread.sleep(6000) // Wait for adjustment interval
        
        var highLoadAccepted = 0
        repeat(100) {
            if (strategy.shouldAccept(highLoad)) {
                strategy.onAccepted()
                highLoadAccepted++
            } else {
                strategy.onRejected()
            }
        }
        
        // Acceptance rate should decrease under high load
        highLoadAccepted shouldBeLessThan 50.toDouble()
    }
    
    test("should track processing times and adjust accordingly") {
        val strategy = AdaptiveBackpressureStrategy()
        val normalLoad = LoadMetrics(
            pendingMessages = 100,
            processingMessages = 10,
            memoryUsagePercent = 40.0,
            cpuUsagePercent = 35.0,
            errorRate = 0.02,
            averageProcessingTime = Duration.ofMillis(10)
        )
        
        // Record fast processing times
        repeat(50) {
            strategy.onAccepted()
            strategy.onCompleted(true, Duration.ofMillis(5))
        }
        
        // Should accept messages with fast processing
        var accepted = 0
        repeat(20) {
            if (strategy.shouldAccept(normalLoad)) {
                accepted++
            }
        }
        accepted shouldBeGreaterThan 15.toLong()
        
        // Record slow processing times
        repeat(50) {
            strategy.onAccepted()
            strategy.onCompleted(true, Duration.ofMillis(100))
        }
        
        // Force adjustment
        Thread.sleep(6000)
        
        // Should be more conservative with slow processing
        accepted = 0
        repeat(20) {
            if (strategy.shouldAccept(normalLoad)) {
                accepted++
            }
        }
        accepted shouldBeLessThan 15.toDouble()
    }
    
    test("should respect configuration limits") {
        val config = BackpressureConfig(
            maxPendingMessages = 500,
            maxMemoryUsage = 70.0,
            maxCpuUsage = 70.0,
            maxErrorRate = 0.05
        )
        val strategy = AdaptiveBackpressureStrategy(config)
        
        // Load exceeding limits
        val overLimitLoad = LoadMetrics(
            pendingMessages = 600, // Exceeds limit
            processingMessages = 50,
            memoryUsagePercent = 65.0,
            cpuUsagePercent = 65.0,
            errorRate = 0.03,
            averageProcessingTime = Duration.ofMillis(15)
        )
        
        // Should reject all due to pending message limit
        repeat(10) {
            strategy.shouldAccept(overLimitLoad) shouldBe false
        }
        
        // Load within limits
        val withinLimitLoad = LoadMetrics(
            pendingMessages = 400,
            processingMessages = 40,
            memoryUsagePercent = 65.0,
            cpuUsagePercent = 65.0,
            errorRate = 0.03,
            averageProcessingTime = Duration.ofMillis(15)
        )
        
        // Should accept some messages
        var accepted = 0
        repeat(10) {
            if (strategy.shouldAccept(withinLimitLoad)) {
                accepted++
            }
        }
        accepted shouldBeGreaterThan 0.toLong()
    }
    
    test("should calculate metrics correctly") {
        val strategy = AdaptiveBackpressureStrategy()
        
        // Record some accepts and rejects
        repeat(70) {
            strategy.onAccepted()
        }
        repeat(30) {
            strategy.onRejected()
        }
        
        val metrics = strategy.getMetrics()
        metrics.totalAccepted shouldBe 70
        metrics.totalRejected shouldBe 30
        metrics.acceptanceRate shouldBe 0.7
    }
    
    test("should reset state correctly") {
        val strategy = AdaptiveBackpressureStrategy()
        
        // Add some data
        repeat(50) {
            strategy.onAccepted()
            strategy.onCompleted(true, Duration.ofMillis(10))
        }
        repeat(20) {
            strategy.onRejected()
        }
        
        // Reset
        strategy.reset()
        
        val metrics = strategy.getMetrics()
        metrics.totalAccepted shouldBe 0
        metrics.totalRejected shouldBe 0
        metrics.acceptanceRate shouldBe 1.0
    }
    
    test("should handle error rates in acceptance decisions") {
        val strategy = AdaptiveBackpressureStrategy()
        
        // Record high error rate
        repeat(50) {
            strategy.onAccepted()
            strategy.onCompleted(false, Duration.ofMillis(20)) // All failures
        }
        
        val highErrorLoad = LoadMetrics(
            pendingMessages = 100,
            processingMessages = 10,
            memoryUsagePercent = 40.0,
            cpuUsagePercent = 35.0,
            errorRate = 0.5, // High error rate
            averageProcessingTime = Duration.ofMillis(20)
        )
        
        // Should be more conservative with high error rate
        Thread.sleep(6000) // Force adjustment
        
        var accepted = 0
        repeat(20) {
            if (strategy.shouldAccept(highErrorLoad)) {
                accepted++
            }
        }
        accepted shouldBeLessThan 10.toDouble()
    }
    
    test("should maintain acceptance rate bounds") {
        val strategy = AdaptiveBackpressureStrategy()
        
        // Extreme high load to force minimum acceptance rate
        val extremeLoad = LoadMetrics(
            pendingMessages = 100000,
            processingMessages = 10000,
            memoryUsagePercent = 99.0,
            cpuUsagePercent = 99.0,
            errorRate = 0.9,
            averageProcessingTime = Duration.ofSeconds(5)
        )
        
        // Force multiple adjustments
        repeat(5) {
            Thread.sleep(6000)
            strategy.shouldAccept(extremeLoad)
        }
        
        // Even with extreme load, should maintain minimum acceptance
        var accepted = 0
        repeat(1000) {
            if (strategy.shouldAccept(extremeLoad)) {
                accepted++
            }
        }
        
        // Should maintain at least 10% acceptance rate
        val acceptanceRate = accepted.toDouble() / 1000
        acceptanceRate shouldBeGreaterThanOrEqual 0.05
        acceptanceRate shouldBeLessThanOrEqual 0.15
    }
})