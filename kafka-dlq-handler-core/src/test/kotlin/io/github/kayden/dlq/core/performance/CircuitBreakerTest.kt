package io.github.kayden.dlq.core.performance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration

class CircuitBreakerTest : FunSpec({
    
    test("should start in closed state") {
        val breaker = DefaultCircuitBreaker()
        breaker.getState() shouldBe CircuitBreakerState.CLOSED
        breaker.allowRequest() shouldBe true
    }
    
    test("should open after consecutive failures") {
        val config = CircuitBreakerConfig(
            failureThreshold = 3,
            openDuration = Duration.ofSeconds(10)
        )
        val breaker = DefaultCircuitBreaker(config)
        
        // Record failures
        repeat(3) {
            breaker.allowRequest() shouldBe true
            breaker.recordFailure()
        }
        
        // Circuit should be open
        breaker.getState() shouldBe CircuitBreakerState.OPEN
        breaker.allowRequest() shouldBe false
    }
    
    test("should open based on failure rate") {
        val config = CircuitBreakerConfig(
            failureThreshold = 10, // High threshold
            failureRateThreshold = 0.5, // 50% failure rate
            slidingWindowSize = Duration.ofSeconds(1)
        )
        val breaker = DefaultCircuitBreaker(config)
        
        // Mix of success and failures (60% failure rate)
        repeat(10) { i ->
            breaker.allowRequest() shouldBe true
            if (i < 6) {
                breaker.recordFailure()
            } else {
                breaker.recordSuccess()
            }
        }
        
        // Should open due to failure rate
        breaker.getState() shouldBe CircuitBreakerState.OPEN
    }
    
    test("should transition to half-open after timeout") {
        val config = CircuitBreakerConfig(
            failureThreshold = 1,
            openDuration = Duration.ofMillis(100) // Short duration for testing
        )
        val breaker = DefaultCircuitBreaker(config)
        
        // Open the circuit
        breaker.allowRequest()
        breaker.recordFailure()
        breaker.getState() shouldBe CircuitBreakerState.OPEN
        
        // Wait for timeout
        delay(150)
        
        // Should transition to half-open
        breaker.allowRequest() shouldBe true
        breaker.getState() shouldBe CircuitBreakerState.HALF_OPEN
    }
    
    test("should close from half-open after successful attempts") {
        val config = CircuitBreakerConfig(
            failureThreshold = 1,
            openDuration = Duration.ofMillis(100),
            halfOpenMaxAttempts = 3
        )
        val breaker = DefaultCircuitBreaker(config)
        
        // Open the circuit
        breaker.allowRequest()
        breaker.recordFailure()
        
        // Wait and transition to half-open
        delay(150)
        breaker.allowRequest() shouldBe true
        breaker.getState() shouldBe CircuitBreakerState.HALF_OPEN
        
        // Record successful attempts
        repeat(3) {
            breaker.recordSuccess()
        }
        
        // Should be closed
        breaker.getState() shouldBe CircuitBreakerState.CLOSED
    }
    
    test("should reopen from half-open on failure") {
        val config = CircuitBreakerConfig(
            failureThreshold = 1,
            openDuration = Duration.ofMillis(100),
            halfOpenMaxAttempts = 3
        )
        val breaker = DefaultCircuitBreaker(config)
        
        // Open the circuit
        breaker.allowRequest()
        breaker.recordFailure()
        
        // Wait and transition to half-open
        delay(150)
        breaker.allowRequest() shouldBe true
        breaker.getState() shouldBe CircuitBreakerState.HALF_OPEN
        
        // Record failure in half-open
        breaker.recordFailure()
        
        // Should reopen
        breaker.getState() shouldBe CircuitBreakerState.OPEN
    }
    
    test("should limit attempts in half-open state") {
        val config = CircuitBreakerConfig(
            failureThreshold = 1,
            openDuration = Duration.ofMillis(100),
            halfOpenMaxAttempts = 3
        )
        val breaker = DefaultCircuitBreaker(config)
        
        // Open and transition to half-open
        breaker.allowRequest()
        breaker.recordFailure()
        delay(150)
        
        // Should allow limited attempts
        repeat(3) {
            breaker.allowRequest() shouldBe true
        }
        
        // Should reject further attempts
        breaker.allowRequest() shouldBe false
        breaker.getState() shouldBe CircuitBreakerState.HALF_OPEN
    }
    
    test("should reset consecutive failures on success") {
        val config = CircuitBreakerConfig(failureThreshold = 3)
        val breaker = DefaultCircuitBreaker(config)
        
        // Two failures
        repeat(2) {
            breaker.allowRequest()
            breaker.recordFailure()
        }
        
        // Success should reset counter
        breaker.recordSuccess()
        
        // Two more failures should not open circuit
        repeat(2) {
            breaker.allowRequest()
            breaker.recordFailure()
        }
        
        breaker.getState() shouldBe CircuitBreakerState.CLOSED
    }
    
    test("should track metrics correctly") {
        val config = CircuitBreakerConfig(
            failureThreshold = 5,
            slidingWindowSize = Duration.ofSeconds(10)
        )
        val breaker = DefaultCircuitBreaker(config)
        
        // Record some activity
        repeat(7) {
            breaker.allowRequest()
            breaker.recordSuccess()
        }
        repeat(3) {
            breaker.allowRequest()
            breaker.recordFailure()
        }
        
        val metrics = breaker.getMetrics()
        metrics.state shouldBe CircuitBreakerState.CLOSED
        metrics.totalRequests shouldBe 10
        metrics.totalFailures shouldBe 3
        metrics.consecutiveFailures shouldBe 3
        metrics.failureRate shouldBe 0.3
        metrics.successRate shouldBe 70.0
        metrics.isBlocking shouldBe false
    }
    
    test("should clean up old records in sliding window") {
        val config = CircuitBreakerConfig(
            failureThreshold = 10,
            failureRateThreshold = 0.5,
            slidingWindowSize = Duration.ofMillis(100)
        )
        val breaker = DefaultCircuitBreaker(config)
        
        // Record old failures
        repeat(8) {
            breaker.allowRequest()
            breaker.recordFailure()
        }
        
        // Wait for window to slide
        delay(150)
        
        // Old failures should not count
        repeat(2) {
            breaker.allowRequest()
            breaker.recordSuccess()
        }
        
        val metrics = breaker.getMetrics()
        metrics.failureRate shouldBe 0.0 // Old failures excluded
        breaker.getState() shouldBe CircuitBreakerState.CLOSED
    }
    
    test("should reset state manually") {
        val config = CircuitBreakerConfig(failureThreshold = 1)
        val breaker = DefaultCircuitBreaker(config)
        
        // Open the circuit
        breaker.allowRequest()
        breaker.recordFailure()
        breaker.getState() shouldBe CircuitBreakerState.OPEN
        
        // Manual reset
        breaker.reset()
        
        breaker.getState() shouldBe CircuitBreakerState.CLOSED
        val metrics = breaker.getMetrics()
        metrics.totalRequests shouldBe 0
        metrics.totalFailures shouldBe 0
        metrics.consecutiveFailures shouldBe 0
    }
    
    test("should validate configuration") {
        shouldThrow<IllegalArgumentException> {
            CircuitBreakerConfig(failureThreshold = 0)
        }
        
        shouldThrow<IllegalArgumentException> {
            CircuitBreakerConfig(failureRateThreshold = -0.1)
        }
        
        shouldThrow<IllegalArgumentException> {
            CircuitBreakerConfig(failureRateThreshold = 1.1)
        }
        
        shouldThrow<IllegalArgumentException> {
            CircuitBreakerConfig(openDuration = Duration.ofSeconds(-1))
        }
        
        shouldThrow<IllegalArgumentException> {
            CircuitBreakerConfig(halfOpenMaxAttempts = 0)
        }
    }
    
    test("should handle edge cases gracefully") {
        val breaker = DefaultCircuitBreaker()
        
        // Record success in open state (shouldn't happen but should handle)
        breaker.allowRequest()
        breaker.recordFailure()
        breaker.recordFailure()
        breaker.recordFailure()
        breaker.recordFailure()
        breaker.recordFailure()
        breaker.getState() shouldBe CircuitBreakerState.OPEN
        
        breaker.recordSuccess() // In open state
        breaker.getState() shouldBe CircuitBreakerState.CLOSED // Should reset
    }
    
    test("should track last open time") {
        val config = CircuitBreakerConfig(failureThreshold = 1)
        val breaker = DefaultCircuitBreaker(config)
        
        breaker.allowRequest()
        breaker.recordFailure()
        
        val metrics = breaker.getMetrics()
        metrics.lastOpenTime.shouldNotBeNull()
        metrics.state shouldBe CircuitBreakerState.OPEN
    }
    
    test("should handle concurrent access") {
        val config = CircuitBreakerConfig(
            failureThreshold = 50,
            failureRateThreshold = 0.5
        )
        val breaker = DefaultCircuitBreaker(config)
        
        // Concurrent requests
        List(10) {
            launch {
                repeat(20) {
                    if (breaker.allowRequest()) {
                        if (it % 2 == 0) {
                            breaker.recordSuccess()
                        } else {
                            breaker.recordFailure()
                        }
                    }
                    delay(1)
                }
            }
        }.forEach { it.join() }
        
        // Should have consistent state
        val metrics = breaker.getMetrics()
        metrics.totalRequests shouldBe metrics.totalFailures + (metrics.totalRequests - metrics.totalFailures)
    }
})