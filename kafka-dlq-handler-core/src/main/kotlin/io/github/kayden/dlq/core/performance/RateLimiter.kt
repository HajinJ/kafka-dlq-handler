package io.github.kayden.dlq.core.performance

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Rate limiter interface for controlling message processing rate.
 * 
 * @since 0.1.0
 */
interface RateLimiter {
    
    /**
     * Attempts to acquire a permit for processing.
     * 
     * @return true if permit was acquired, false otherwise
     */
    suspend fun tryAcquire(): Boolean
    
    /**
     * Attempts to acquire multiple permits.
     * 
     * @param permits Number of permits to acquire
     * @return true if all permits were acquired, false otherwise
     */
    suspend fun tryAcquire(permits: Int): Boolean
    
    /**
     * Gets the current rate limit.
     * 
     * @return Current rate limit (permits per second)
     */
    fun getRateLimit(): Double
    
    /**
     * Updates the rate limit dynamically.
     * 
     * @param newRate New rate limit (permits per second)
     */
    fun updateRateLimit(newRate: Double)
    
    /**
     * Gets current rate limiter metrics.
     * 
     * @return Current metrics
     */
    fun getMetrics(): RateLimiterMetrics
}

/**
 * Token bucket implementation of rate limiter.
 * 
 * This implementation uses a token bucket algorithm which allows
 * for burst traffic while maintaining an average rate limit.
 * 
 * @property initialRate Initial rate limit (permits per second)
 * @property burstSize Maximum burst size (defaults to rate)
 * @since 0.1.0
 */
class TokenBucketRateLimiter(
    initialRate: Double,
    private val burstSize: Int = initialRate.toInt()
) : RateLimiter {
    
    init {
        require(initialRate > 0) { "Rate must be positive" }
        require(burstSize > 0) { "Burst size must be positive" }
    }
    
    private val mutex = Mutex()
    
    @Volatile
    private var rate = initialRate
    private var tokens = burstSize.toDouble()
    private var lastRefillTime = Instant.now()
    
    private val acquiredCount = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)
    
    override suspend fun tryAcquire(): Boolean = tryAcquire(1)
    
    override suspend fun tryAcquire(permits: Int): Boolean = mutex.withLock {
        require(permits > 0) { "Permits must be positive" }
        require(permits <= burstSize) { "Permits cannot exceed burst size" }
        
        refillTokens()
        
        return if (tokens >= permits) {
            tokens -= permits
            acquiredCount.addAndGet(permits.toLong())
            true
        } else {
            rejectedCount.addAndGet(permits.toLong())
            false
        }
    }
    
    override fun getRateLimit(): Double = rate
    
    override fun updateRateLimit(newRate: Double) {
        require(newRate > 0) { "Rate must be positive" }
        rate = newRate
    }
    
    override fun getMetrics(): RateLimiterMetrics {
        return RateLimiterMetrics(
            currentRate = rate,
            acquiredPermits = acquiredCount.get(),
            rejectedPermits = rejectedCount.get(),
            availableTokens = tokens.toInt()
        )
    }
    
    private fun refillTokens() {
        val now = Instant.now()
        val elapsed = Duration.between(lastRefillTime, now)
        
        if (elapsed.toMillis() > 0) {
            val tokensToAdd = (elapsed.toMillis() / 1000.0) * rate
            tokens = min(tokens + tokensToAdd, burstSize.toDouble())
            lastRefillTime = now
        }
    }
}

/**
 * Sliding window rate limiter implementation.
 * 
 * This implementation tracks requests in a sliding time window,
 * providing more accurate rate limiting than token bucket.
 * 
 * @property rate Rate limit (permits per second)
 * @property windowSize Size of the sliding window
 * @since 0.1.0
 */
class SlidingWindowRateLimiter(
    private var rate: Double,
    private val windowSize: Duration = Duration.ofSeconds(1)
) : RateLimiter {
    
    init {
        require(rate > 0) { "Rate must be positive" }
        require(!windowSize.isNegative && !windowSize.isZero) { "Window size must be positive" }
    }
    
    private val mutex = Mutex()
    private val requests = mutableListOf<Instant>()
    
    private val acquiredCount = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)
    
    override suspend fun tryAcquire(): Boolean = tryAcquire(1)
    
    override suspend fun tryAcquire(permits: Int): Boolean = mutex.withLock {
        require(permits > 0) { "Permits must be positive" }
        
        val now = Instant.now()
        cleanupOldRequests(now)
        
        val maxPermits = (rate * windowSize.toMillis() / 1000.0).toInt()
        
        return if (requests.size + permits <= maxPermits) {
            repeat(permits) {
                requests.add(now)
            }
            acquiredCount.addAndGet(permits.toLong())
            true
        } else {
            rejectedCount.addAndGet(permits.toLong())
            false
        }
    }
    
    override fun getRateLimit(): Double = rate
    
    override fun updateRateLimit(newRate: Double) {
        require(newRate > 0) { "Rate must be positive" }
        rate = newRate
    }
    
    override fun getMetrics(): RateLimiterMetrics {
        return RateLimiterMetrics(
            currentRate = rate,
            acquiredPermits = acquiredCount.get(),
            rejectedPermits = rejectedCount.get(),
            availableTokens = 0 // Not applicable for sliding window
        )
    }
    
    private fun cleanupOldRequests(now: Instant) {
        val cutoff = now.minus(windowSize)
        requests.removeIf { it < cutoff }
    }
}

/**
 * Metrics for rate limiter performance.
 * 
 * @property currentRate Current rate limit (permits per second)
 * @property acquiredPermits Total permits acquired
 * @property rejectedPermits Total permits rejected
 * @property availableTokens Currently available tokens (for token bucket)
 * @since 0.1.0
 */
data class RateLimiterMetrics(
    val currentRate: Double,
    val acquiredPermits: Long,
    val rejectedPermits: Long,
    val availableTokens: Int
) {
    /**
     * Total permit requests.
     */
    val totalRequests: Long = acquiredPermits + rejectedPermits
    
    /**
     * Acceptance rate as a percentage.
     */
    val acceptanceRate: Double = if (totalRequests > 0) {
        (acquiredPermits.toDouble() / totalRequests) * 100
    } else {
        100.0
    }
}