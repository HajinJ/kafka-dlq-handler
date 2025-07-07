package io.github.kayden.dlq.core.performance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis

class TokenBucketRateLimiterTest : FunSpec({
    
    test("should allow burst traffic up to burst size") {
        val rate = 10.0 // 10 permits per second
        val burstSize = 20
        val limiter = TokenBucketRateLimiter(rate, burstSize)
        
        // Should allow burst of 20 immediately
        var acquired = 0
        runBlocking {
            repeat(25) {
                if (limiter.tryAcquire()) {
                    acquired++
                }
            }
            
            acquired shouldBe burstSize // Should acquire exactly burst size
        }
    }
    
    test("should refill tokens at configured rate") {
        val rate = 100.0 // 100 permits per second
        val limiter = TokenBucketRateLimiter(rate, 10)
        
        runBlocking {
            // Exhaust tokens
            repeat(10) {
                limiter.tryAcquire() shouldBe true
            }
            limiter.tryAcquire() shouldBe false // Should be empty
            
            // Wait for refill
            delay(100) // 0.1 second = 10 tokens
            
            // Should be able to acquire ~10 tokens
            var acquired = 0
            repeat(15) {
                if (limiter.tryAcquire()) {
                    acquired++
                }
            }
            
            acquired shouldBeGreaterThan 8 // Allow some timing variance
            acquired shouldBeLessThan 12
        }
    }
    
    test("should respect rate limit over time") {
        val rate = 50.0 // 50 permits per second
        val limiter = TokenBucketRateLimiter(rate, 10)
        
        var totalAcquired = 0
        val duration = measureTimeMillis {
            runBlocking {
                repeat(100) {
                    if (limiter.tryAcquire()) {
                        totalAcquired++
                    }
                    delay(10) // Spread requests over time
                }
            }
        }
        
        // In ~1 second, should acquire ~50 permits
        val expectedPermits = (rate * duration / 1000).toInt()
        totalAcquired shouldBeGreaterThan (expectedPermits - 10)
        totalAcquired shouldBeLessThan (expectedPermits + 10)
    }
    
    test("should handle multiple permits at once") {
        val rate = 20.0
        val burstSize = 30
        val limiter = TokenBucketRateLimiter(rate, burstSize)
        
        runBlocking {
            // Should succeed for permits within burst
            limiter.tryAcquire(10) shouldBe true
            limiter.tryAcquire(10) shouldBe true
            limiter.tryAcquire(10) shouldBe true
            limiter.tryAcquire(5) shouldBe false // Exceeds available tokens
        }
        
        val metrics = limiter.getMetrics()
        metrics.acquiredPermits shouldBe 30
        metrics.rejectedPermits shouldBe 5
    }
    
    test("should reject permits exceeding burst size") {
        val limiter = TokenBucketRateLimiter(10.0, 20)
        
        runBlocking {
            shouldThrow<IllegalArgumentException> {
                limiter.tryAcquire(21)
            }
        }
    }
    
    test("should update rate limit dynamically") {
        val limiter = TokenBucketRateLimiter(10.0, 20)
        
        var acquired = 0
        runBlocking {
            // Exhaust tokens
            repeat(20) {
                limiter.tryAcquire()
            }
            
            // Update rate
            limiter.updateRateLimit(100.0)
            
            // Wait for faster refill
            delay(100) // 0.1 second = 10 tokens at new rate
            
            repeat(15) {
                if (limiter.tryAcquire()) {
                    acquired++
                }
            }
            
            acquired shouldBeGreaterThan 8 // Should refill faster
        }
    }
    
    test("should track metrics correctly") {
        val limiter = TokenBucketRateLimiter(50.0, 10)
        
        runBlocking {
            // Acquire some permits
            repeat(8) {
                limiter.tryAcquire() shouldBe true
            }
            
            // Try to acquire more than available
            repeat(5) {
                limiter.tryAcquire() shouldBe false
            }
        }
        
        val metrics = limiter.getMetrics()
        metrics.currentRate shouldBe 50.0
        metrics.acquiredPermits shouldBe 8
        metrics.rejectedPermits shouldBe 5
        metrics.availableTokens shouldBe 2 // 10 - 8
        metrics.totalRequests shouldBe 13
        metrics.acceptanceRate shouldBeGreaterThan 61.0
        metrics.acceptanceRate shouldBeLessThan 62.0
    }
    
    test("should not exceed burst size even with refill") {
        val rate = 1000.0 // Very high rate
        val burstSize = 10
        val limiter = TokenBucketRateLimiter(rate, burstSize)
        
        var acquired = 0
        runBlocking {
            // Wait for potential over-refill
            delay(100)
            
            // Should still be limited to burst size
            repeat(20) {
                if (limiter.tryAcquire()) {
                    acquired++
                }
            }
            
            acquired shouldBe burstSize
        }
    }
    
    test("should handle zero permits correctly") {
        val limiter = TokenBucketRateLimiter(10.0, 20)
        
        runBlocking {
            shouldThrow<IllegalArgumentException> {
                limiter.tryAcquire(0)
            }
            
            shouldThrow<IllegalArgumentException> {
                limiter.tryAcquire(-1)
            }
        }
    }
    
    test("should validate constructor parameters") {
        shouldThrow<IllegalArgumentException> {
            TokenBucketRateLimiter(0.0, 10) // Zero rate
        }
        
        shouldThrow<IllegalArgumentException> {
            TokenBucketRateLimiter(-1.0, 10) // Negative rate
        }
        
        shouldThrow<IllegalArgumentException> {
            TokenBucketRateLimiter(10.0, 0) // Zero burst size
        }
        
        shouldThrow<IllegalArgumentException> {
            TokenBucketRateLimiter(10.0, -1) // Negative burst size
        }
    }
    
    test("should handle concurrent access correctly") {
        val rate = 100.0
        val burstSize = 50
        val limiter = TokenBucketRateLimiter(rate, burstSize)
        
        var totalAcquired = 0
        
        // Simulate concurrent access
        kotlinx.coroutines.runBlocking {
            List(10) { 
                launch {
                    repeat(10) {
                        if (limiter.tryAcquire()) {
                            totalAcquired++
                        }
                        delay(5)
                    }
                }
            }.forEach { it.join() }
        }
        
        // Should not exceed burst size initially
        totalAcquired shouldBeLessThan (burstSize + rate * 0.5).toInt()
        
        val metrics = limiter.getMetrics()
        metrics.acquiredPermits shouldBe totalAcquired.toLong()
        metrics.acquiredPermits + metrics.rejectedPermits shouldBe 100 // Total attempts
    }
})