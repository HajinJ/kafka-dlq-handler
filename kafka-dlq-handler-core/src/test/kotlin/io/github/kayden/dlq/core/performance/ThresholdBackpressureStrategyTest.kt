package io.github.kayden.dlq.core.performance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

class ThresholdBackpressureStrategyTest : FunSpec({
    
    test("should accept when all metrics are below thresholds") {
        val config = BackpressureConfig(
            maxPendingMessages = 1000,
            maxMemoryUsage = 80.0,
            maxCpuUsage = 80.0,
            maxErrorRate = 0.1
        )
        val strategy = ThresholdBackpressureStrategy(config)
        
        val lowLoad = LoadMetrics(
            pendingMessages = 500,
            processingMessages = 50,
            memoryUsagePercent = 60.0,
            cpuUsagePercent = 60.0,
            errorRate = 0.05,
            averageProcessingTime = Duration.ofMillis(10)
        )
        
        strategy.shouldAccept(lowLoad) shouldBe true
    }
    
    test("should reject when any metric exceeds threshold") {
        val config = BackpressureConfig(
            maxPendingMessages = 1000,
            maxMemoryUsage = 80.0,
            maxCpuUsage = 80.0,
            maxErrorRate = 0.1
        )
        val strategy = ThresholdBackpressureStrategy(config)
        
        // Test each threshold independently
        
        // Pending messages exceed threshold
        val highPendingLoad = LoadMetrics(
            pendingMessages = 1001,
            processingMessages = 50,
            memoryUsagePercent = 60.0,
            cpuUsagePercent = 60.0,
            errorRate = 0.05,
            averageProcessingTime = Duration.ofMillis(10)
        )
        strategy.shouldAccept(highPendingLoad) shouldBe false
        
        // Memory usage exceeds threshold
        val highMemoryLoad = LoadMetrics(
            pendingMessages = 500,
            processingMessages = 50,
            memoryUsagePercent = 80.1,
            cpuUsagePercent = 60.0,
            errorRate = 0.05,
            averageProcessingTime = Duration.ofMillis(10)
        )
        strategy.shouldAccept(highMemoryLoad) shouldBe false
        
        // CPU usage exceeds threshold
        val highCpuLoad = LoadMetrics(
            pendingMessages = 500,
            processingMessages = 50,
            memoryUsagePercent = 60.0,
            cpuUsagePercent = 80.1,
            errorRate = 0.05,
            averageProcessingTime = Duration.ofMillis(10)
        )
        strategy.shouldAccept(highCpuLoad) shouldBe false
        
        // Error rate exceeds threshold
        val highErrorLoad = LoadMetrics(
            pendingMessages = 500,
            processingMessages = 50,
            memoryUsagePercent = 60.0,
            cpuUsagePercent = 60.0,
            errorRate = 0.11,
            averageProcessingTime = Duration.ofMillis(10)
        )
        strategy.shouldAccept(highErrorLoad) shouldBe false
    }
    
    test("should accept at exact thresholds") {
        val config = BackpressureConfig(
            maxPendingMessages = 1000,
            maxMemoryUsage = 80.0,
            maxCpuUsage = 80.0,
            maxErrorRate = 0.1
        )
        val strategy = ThresholdBackpressureStrategy(config)
        
        val exactThresholdLoad = LoadMetrics(
            pendingMessages = 1000,
            processingMessages = 50,
            memoryUsagePercent = 80.0,
            cpuUsagePercent = 80.0,
            errorRate = 0.1,
            averageProcessingTime = Duration.ofMillis(10)
        )
        
        strategy.shouldAccept(exactThresholdLoad) shouldBe true
    }
    
    test("should track accepted and rejected counts") {
        val strategy = ThresholdBackpressureStrategy()
        val normalLoad = LoadMetrics(
            pendingMessages = 100,
            processingMessages = 10,
            memoryUsagePercent = 40.0,
            cpuUsagePercent = 35.0,
            errorRate = 0.02,
            averageProcessingTime = Duration.ofMillis(10)
        )
        
        // Accept some messages
        repeat(70) {
            if (strategy.shouldAccept(normalLoad)) {
                strategy.onAccepted()
            }
        }
        
        // Simulate rejection scenario
        val highLoad = LoadMetrics(
            pendingMessages = 20000, // Exceeds default threshold
            processingMessages = 1000,
            memoryUsagePercent = 90.0,
            cpuUsagePercent = 90.0,
            errorRate = 0.2,
            averageProcessingTime = Duration.ofMillis(100)
        )
        
        repeat(30) {
            if (!strategy.shouldAccept(highLoad)) {
                strategy.onRejected()
            }
        }
        
        val metrics = strategy.getMetrics()
        metrics.totalAccepted shouldBe 70
        metrics.totalRejected shouldBe 30
        metrics.acceptanceRate shouldBe 0.7
    }
    
    test("should ignore onCompleted calls") {
        val strategy = ThresholdBackpressureStrategy()
        
        // Should not affect behavior
        strategy.onCompleted(true, Duration.ofMillis(10))
        strategy.onCompleted(false, Duration.ofMillis(20))
        
        val metrics = strategy.getMetrics()
        metrics.totalAccepted shouldBe 0
        metrics.totalRejected shouldBe 0
    }
    
    test("should reset state correctly") {
        val strategy = ThresholdBackpressureStrategy()
        
        // Add some data
        repeat(50) {
            strategy.onAccepted()
        }
        repeat(20) {
            strategy.onRejected()
        }
        
        val beforeReset = strategy.getMetrics()
        beforeReset.totalAccepted shouldBe 50
        beforeReset.totalRejected shouldBe 20
        
        // Reset
        strategy.reset()
        
        val afterReset = strategy.getMetrics()
        afterReset.totalAccepted shouldBe 0
        afterReset.totalRejected shouldBe 0
        afterReset.acceptanceRate shouldBe 1.0
    }
    
    test("should work with different preset configurations") {
        // Test with aggressive config
        val aggressiveStrategy = ThresholdBackpressureStrategy(BackpressureConfig.AGGRESSIVE)
        val moderateLoad = LoadMetrics(
            pendingMessages = 1500, // Exceeds aggressive limit (1000)
            processingMessages = 100,
            memoryUsagePercent = 65.0,
            cpuUsagePercent = 65.0,
            errorRate = 0.02,
            averageProcessingTime = Duration.ofMillis(15)
        )
        aggressiveStrategy.shouldAccept(moderateLoad) shouldBe false
        
        // Test with relaxed config
        val relaxedStrategy = ThresholdBackpressureStrategy(BackpressureConfig.RELAXED)
        relaxedStrategy.shouldAccept(moderateLoad) shouldBe true // Same load accepted
        
        // Even extreme load might be accepted with relaxed config
        val extremeLoad = LoadMetrics(
            pendingMessages = 40000,
            processingMessages = 5000,
            memoryUsagePercent = 94.0,
            cpuUsagePercent = 94.0,
            errorRate = 0.15,
            averageProcessingTime = Duration.ofMillis(100)
        )
        relaxedStrategy.shouldAccept(extremeLoad) shouldBe true
    }
    
    test("should calculate metrics correctly with no traffic") {
        val strategy = ThresholdBackpressureStrategy()
        
        val metrics = strategy.getMetrics()
        metrics.totalAccepted shouldBe 0
        metrics.totalRejected shouldBe 0
        metrics.acceptanceRate shouldBe 1.0 // Default when no traffic
        metrics.currentThroughput shouldBe 0.0
    }
})