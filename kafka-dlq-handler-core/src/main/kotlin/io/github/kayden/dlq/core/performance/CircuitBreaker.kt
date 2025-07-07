package io.github.kayden.dlq.core.performance

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Circuit breaker pattern implementation for fault tolerance.
 * 
 * The circuit breaker prevents cascading failures by monitoring
 * error rates and temporarily blocking requests when failures exceed
 * a threshold.
 * 
 * @since 0.1.0
 */
interface CircuitBreaker {
    
    /**
     * Checks if the circuit breaker allows the request.
     * 
     * @return true if the request is allowed, false if circuit is open
     */
    suspend fun allowRequest(): Boolean
    
    /**
     * Records a successful operation.
     */
    fun recordSuccess()
    
    /**
     * Records a failed operation.
     */
    fun recordFailure()
    
    /**
     * Gets the current state of the circuit breaker.
     * 
     * @return Current state
     */
    fun getState(): CircuitBreakerState
    
    /**
     * Gets current circuit breaker metrics.
     * 
     * @return Current metrics
     */
    fun getMetrics(): CircuitBreakerMetrics
    
    /**
     * Manually resets the circuit breaker to closed state.
     */
    fun reset()
}

/**
 * States of the circuit breaker.
 * 
 * @since 0.1.0
 */
enum class CircuitBreakerState {
    /**
     * Circuit is closed - requests are allowed.
     */
    CLOSED,
    
    /**
     * Circuit is open - requests are blocked.
     */
    OPEN,
    
    /**
     * Circuit is half-open - limited requests are allowed for testing.
     */
    HALF_OPEN
}

/**
 * Configuration for circuit breaker.
 * 
 * @property failureThreshold Number of failures before opening circuit
 * @property failureRateThreshold Failure rate threshold (0-1)
 * @property openDuration Duration to keep circuit open
 * @property halfOpenMaxAttempts Max attempts in half-open state
 * @property slidingWindowSize Size of sliding window for metrics
 * @since 0.1.0
 */
data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val failureRateThreshold: Double = 0.5,
    val openDuration: Duration = Duration.ofSeconds(60),
    val halfOpenMaxAttempts: Int = 3,
    val slidingWindowSize: Duration = Duration.ofMinutes(1)
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(failureRateThreshold in 0.0..1.0) { "failureRateThreshold must be between 0 and 1" }
        require(!openDuration.isNegative) { "openDuration must not be negative" }
        require(halfOpenMaxAttempts > 0) { "halfOpenMaxAttempts must be positive" }
        require(!slidingWindowSize.isNegative) { "slidingWindowSize must not be negative" }
    }
}

/**
 * Default implementation of circuit breaker.
 * 
 * @property config Circuit breaker configuration
 * @since 0.1.0
 */
class DefaultCircuitBreaker(
    private val config: CircuitBreakerConfig = CircuitBreakerConfig()
) : CircuitBreaker {
    
    private val state = AtomicReference(CircuitBreakerState.CLOSED)
    private val mutex = Mutex()
    
    // Metrics
    private val consecutiveFailures = AtomicInteger(0)
    private val halfOpenAttempts = AtomicInteger(0)
    private val totalRequests = AtomicLong(0)
    private val totalFailures = AtomicLong(0)
    
    // Sliding window for failure rate calculation
    private val requestHistory = mutableListOf<RequestRecord>()
    
    // State transition timestamps
    @Volatile
    private var lastOpenTime: Instant? = null
    
    override suspend fun allowRequest(): Boolean = mutex.withLock {
        totalRequests.incrementAndGet()
        
        when (state.get()) {
            CircuitBreakerState.CLOSED -> true
            
            CircuitBreakerState.OPEN -> {
                val openTime = lastOpenTime
                if (openTime != null && Duration.between(openTime, Instant.now()) >= config.openDuration) {
                    transitionToHalfOpen()
                    true
                } else {
                    false
                }
            }
            
            CircuitBreakerState.HALF_OPEN -> {
                halfOpenAttempts.incrementAndGet() <= config.halfOpenMaxAttempts
            }
        }
    }
    
    override fun recordSuccess() {
        recordRequest(true)
        
        when (state.get()) {
            CircuitBreakerState.CLOSED -> {
                consecutiveFailures.set(0)
            }
            
            CircuitBreakerState.HALF_OPEN -> {
                if (halfOpenAttempts.get() >= config.halfOpenMaxAttempts) {
                    transitionToClosed()
                }
            }
            
            CircuitBreakerState.OPEN -> {
                // Should not happen, but reset if it does
                transitionToClosed()
            }
        }
    }
    
    override fun recordFailure() {
        recordRequest(false)
        totalFailures.incrementAndGet()
        
        when (state.get()) {
            CircuitBreakerState.CLOSED -> {
                val failures = consecutiveFailures.incrementAndGet()
                
                if (failures >= config.failureThreshold || 
                    calculateFailureRate() >= config.failureRateThreshold) {
                    transitionToOpen()
                }
            }
            
            CircuitBreakerState.HALF_OPEN -> {
                transitionToOpen()
            }
            
            CircuitBreakerState.OPEN -> {
                // Already open, do nothing
            }
        }
    }
    
    override fun getState(): CircuitBreakerState = state.get()
    
    override fun getMetrics(): CircuitBreakerMetrics {
        cleanupOldRecords()
        
        return CircuitBreakerMetrics(
            state = state.get(),
            totalRequests = totalRequests.get(),
            totalFailures = totalFailures.get(),
            consecutiveFailures = consecutiveFailures.get(),
            failureRate = calculateFailureRate(),
            lastOpenTime = lastOpenTime,
            halfOpenAttempts = if (state.get() == CircuitBreakerState.HALF_OPEN) {
                halfOpenAttempts.get()
            } else {
                0
            }
        )
    }
    
    override fun reset() {
        transitionToClosed()
        consecutiveFailures.set(0)
        halfOpenAttempts.set(0)
        totalRequests.set(0)
        totalFailures.set(0)
        requestHistory.clear()
        lastOpenTime = null
    }
    
    private fun transitionToClosed() {
        state.set(CircuitBreakerState.CLOSED)
        consecutiveFailures.set(0)
        halfOpenAttempts.set(0)
    }
    
    private fun transitionToOpen() {
        state.set(CircuitBreakerState.OPEN)
        lastOpenTime = Instant.now()
        halfOpenAttempts.set(0)
    }
    
    private fun transitionToHalfOpen() {
        state.set(CircuitBreakerState.HALF_OPEN)
        halfOpenAttempts.set(0)
    }
    
    private fun recordRequest(success: Boolean) {
        val record = RequestRecord(Instant.now(), success)
        requestHistory.add(record)
        cleanupOldRecords()
    }
    
    private fun calculateFailureRate(): Double {
        cleanupOldRecords()
        
        if (requestHistory.isEmpty()) {
            return 0.0
        }
        
        val failures = requestHistory.count { !it.success }
        return failures.toDouble() / requestHistory.size
    }
    
    private fun cleanupOldRecords() {
        val cutoff = Instant.now().minus(config.slidingWindowSize)
        requestHistory.removeIf { it.timestamp < cutoff }
    }
    
    private data class RequestRecord(
        val timestamp: Instant,
        val success: Boolean
    )
}

/**
 * Metrics for circuit breaker.
 * 
 * @property state Current state
 * @property totalRequests Total number of requests
 * @property totalFailures Total number of failures
 * @property consecutiveFailures Current consecutive failures
 * @property failureRate Current failure rate (0-1)
 * @property lastOpenTime Time when circuit was last opened
 * @property halfOpenAttempts Attempts made in half-open state
 * @since 0.1.0
 */
data class CircuitBreakerMetrics(
    val state: CircuitBreakerState,
    val totalRequests: Long,
    val totalFailures: Long,
    val consecutiveFailures: Int,
    val failureRate: Double,
    val lastOpenTime: Instant?,
    val halfOpenAttempts: Int
) {
    /**
     * Success rate as a percentage.
     */
    val successRate: Double = if (totalRequests > 0) {
        ((totalRequests - totalFailures).toDouble() / totalRequests) * 100
    } else {
        100.0
    }
    
    /**
     * Whether the circuit is currently blocking requests.
     */
    val isBlocking: Boolean = state == CircuitBreakerState.OPEN
}