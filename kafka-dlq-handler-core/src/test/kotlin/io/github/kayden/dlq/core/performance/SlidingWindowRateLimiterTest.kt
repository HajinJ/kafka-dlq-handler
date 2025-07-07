package io.github.kayden.dlq.core.performance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import kotlin.system.measureTimeMillis

class SlidingWindowRateLimiterTest : FunSpec({
    
    test("should enforce rate limit within window") {
        val rate = 10.0 // 10 permits per second
        val window = Duration.ofSeconds(1)
        val limiter = SlidingWindowRateLimiter(rate, window)
        
        // Should allow 10 permits in the window
        repeat(10) {
            limiter.tryAcquire() shouldBe true
        }
        
        // 11th should fail
        limiter.tryAcquire() shouldBe false
    }
    
    test("should slide window and allow new permits") {
        val rate = 100.0 // 100 permits per second
        val window = Duration.ofMillis(100) // 0.1 second window
        val limiter = SlidingWindowRateLimiter(rate, window)
        
        // Window allows 10 permits (100 * 0.1)
        repeat(10) {
            limiter.tryAcquire() shouldBe true
        }
        limiter.tryAcquire() shouldBe false
        
        // Wait for window to slide
        delay(110) // Slightly more than window size
        
        // Should allow new permits
        repeat(10) {
            limiter.tryAcquire() shouldBe true
        }
    }
    
    test("should accurately track requests over time") {
        val rate = 50.0 // 50 permits per second
        val limiter = SlidingWindowRateLimiter(rate)
        
        var totalAcquired = 0
        val duration = measureTimeMillis {
            repeat(100) {
                if (limiter.tryAcquire()) {
                    totalAcquired++
                }
                delay(10) // Spread over ~1 second
            }
        }
        
        // Should be close to rate * duration
        val expectedPermits = (rate * duration / 1000).toLong()
        totalAcquired.toLong() shouldBeGreaterThan (expectedPermits - 5)
        totalAcquired.toLong() shouldBeLessThan (expectedPermits + 5)
    }
    
    test("should handle multiple permits correctly") {
        val rate = 20.0 // 20 permits per second
        val limiter = SlidingWindowRateLimiter(rate)
        
        // Should allow 20 permits total in 1 second window
        limiter.tryAcquire(5) shouldBe true
        limiter.tryAcquire(5) shouldBe true
        limiter.tryAcquire(5) shouldBe true
        limiter.tryAcquire(5) shouldBe true
        limiter.tryAcquire(1) shouldBe false // Would exceed limit
        
        val metrics = limiter.getMetrics()
        metrics.acquiredPermits shouldBe 20
        metrics.rejectedPermits shouldBe 1
    }
    
    test("should clean up old requests") {
        val rate = 1000.0 // High rate
        val window = Duration.ofMillis(100)
        val limiter = SlidingWindowRateLimiter(rate, window)
        
        // Acquire some permits
        repeat(50) {
            limiter.tryAcquire()
        }
        
        // Wait for window to expire
        delay(150)
        
        // All old requests should be cleaned up
        // Should be able to acquire full window again
        var acquired = 0
        repeat(100) {
            if (limiter.tryAcquire()) {
                acquired++
            }
        }
        
        acquired shouldBe 100 // Full window capacity
    }
    
    test("should update rate limit dynamically") {
        val limiter = SlidingWindowRateLimiter(10.0)
        
        // Use initial rate
        repeat(10) {
            limiter.tryAcquire() shouldBe true
        }
        limiter.tryAcquire() shouldBe false
        
        // Update to higher rate
        limiter.updateRateLimit(20.0)
        
        // Should immediately allow more permits
        repeat(10) {
            limiter.tryAcquire() shouldBe true
        }
    }
    
    test("should track metrics correctly") {
        val limiter = SlidingWindowRateLimiter(50.0)
        
        // Acquire some permits
        repeat(30) {
            limiter.tryAcquire() shouldBe true
        }
        
        // Try to exceed limit
        repeat(25) {
            limiter.tryAcquire() shouldBe false
        }
        
        val metrics = limiter.getMetrics()
        metrics.currentRate shouldBe 50.0
        metrics.acquiredPermits shouldBe 30
        metrics.rejectedPermits shouldBe 25
        metrics.availableTokens shouldBe 0 // Not applicable for sliding window
        metrics.totalRequests shouldBe 55
        metrics.acceptanceRate shouldBeGreaterThan 54.0
        metrics.acceptanceRate shouldBeLessThan 55.0
    }
    
    test("should handle different window sizes") {
        // Small window
        val smallWindowLimiter = SlidingWindowRateLimiter(100.0, Duration.ofMillis(10))
        // Should allow 1 permit in 10ms window
        smallWindowLimiter.tryAcquire() shouldBe true
        smallWindowLimiter.tryAcquire() shouldBe false
        
        // Large window
        val largeWindowLimiter = SlidingWindowRateLimiter(1.0, Duration.ofSeconds(10))
        // Should allow 10 permits in 10 second window
        repeat(10) {
            largeWindowLimiter.tryAcquire() shouldBe true
        }
        largeWindowLimiter.tryAcquire() shouldBe false
    }
    
    test("should validate constructor parameters") {
        shouldThrow<IllegalArgumentException> {
            SlidingWindowRateLimiter(0.0) // Zero rate
        }
        
        shouldThrow<IllegalArgumentException> {
            SlidingWindowRateLimiter(-1.0) // Negative rate
        }
        
        shouldThrow<IllegalArgumentException> {
            SlidingWindowRateLimiter(10.0, Duration.ZERO) // Zero window
        }
        
        shouldThrow<IllegalArgumentException> {
            SlidingWindowRateLimiter(10.0, Duration.ofSeconds(-1)) // Negative window
        }
    }
    
    test("should handle concurrent access") {
        val rate = 100.0
        val limiter = SlidingWindowRateLimiter(rate)
        
        var totalAcquired = 0
        var totalRejected = 0
        
        // Concurrent attempts
        List(10) {
            launch {
                repeat(20) {
                    if (limiter.tryAcquire()) {
                        totalAcquired++
                    } else {
                        totalRejected++
                    }
                    delay(5)
                }
            }
        }.forEach { it.join() }
        
        // Total attempts
        totalAcquired + totalRejected shouldBe 200
        
        // Should respect rate limit
        val metrics = limiter.getMetrics()
        metrics.acquiredPermits shouldBe totalAcquired.toLong()
        metrics.rejectedPermits shouldBe totalRejected.toLong()
    }
    
    test("should maintain precision with fractional rates") {
        val rate = 2.5 // 2.5 permits per second
        val window = Duration.ofSeconds(2) // 2 second window = 5 permits
        val limiter = SlidingWindowRateLimiter(rate, window)
        
        // Should allow exactly 5 permits
        repeat(5) {
            limiter.tryAcquire() shouldBe true
        }
        limiter.tryAcquire() shouldBe false
    }
})