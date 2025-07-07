package io.github.kayden.dlq.core.dsl

import io.github.kayden.dlq.core.performance.*
import java.time.Duration
import kotlin.time.Duration as KotlinDuration
import kotlin.time.toJavaDuration


/**
 * Builder for performance configuration using DSL.
 * 
 * Example:
 * ```kotlin
 * performance {
 *     mode = ProcessingMode.PARALLEL
 *     
 *     batch {
 *         size = 5000
 *         timeout = 200.milliseconds
 *         adaptive = true
 *     }
 *     
 *     parallel {
 *         workers = 16
 *         partitions = 64
 *         workStealing = true
 *     }
 *     
 *     backpressure {
 *         enabled = true
 *         maxPending = 500_000
 *         strategy = adaptive {
 *             threshold = 0.85
 *         }
 *     }
 * }
 * ```
 * 
 * @since 0.1.0
 */
@DLQHandlerDslMarker
class PerformanceConfigBuilder {
    var mode: ProcessingMode = ProcessingMode.BATCH
    
    private var batchConfig = BatchConfig()
    private var parallelConfig = ParallelConfig()
    private var backpressureConfig = BackpressureConfigBuilder()
    private var bufferConfig = BufferConfig()
    
    /**
     * Configures batch processing settings.
     */
    fun batch(block: BatchConfig.() -> Unit) {
        batchConfig.apply(block)
    }
    
    /**
     * Configures parallel processing settings.
     */
    fun parallel(block: ParallelConfig.() -> Unit) {
        parallelConfig.apply(block)
    }
    
    /**
     * Configures backpressure settings.
     */
    fun backpressure(block: BackpressureConfigBuilder.() -> Unit) {
        backpressureConfig.apply(block)
    }
    
    /**
     * Configures buffer settings.
     */
    fun buffer(block: BufferConfig.() -> Unit) {
        bufferConfig.apply(block)
    }
    
    /**
     * Enables high throughput preset.
     */
    fun highThroughput() {
        mode = ProcessingMode.PARALLEL
        batchConfig.apply {
            size = 10000
            minSize = 1000
            timeout = Duration.ofMillis(500)
            adaptive = true
        }
        parallelConfig.apply {
            workers = Runtime.getRuntime().availableProcessors() * 2
            partitions = workers * 8
            workStealing = true
            cpuAffinity = false
        }
        bufferConfig.apply {
            useRingBuffer = true
            ringBufferSize = 131072 // 128K
        }
    }
    
    /**
     * Enables low latency preset.
     */
    fun lowLatency() {
        mode = ProcessingMode.BATCH
        batchConfig.apply {
            size = 100
            minSize = 1
            timeout = Duration.ofMillis(10)
            adaptive = false
        }
        parallelConfig.apply {
            workers = Runtime.getRuntime().availableProcessors()
            workStealing = false
        }
        bufferConfig.apply {
            useRingBuffer = true
            ringBufferSize = 8192
        }
    }
    
    /**
     * Builds the performance configuration.
     */
    fun build(): PerformanceConfiguration {
        return PerformanceConfiguration(
            processingMode = mode,
            batchSize = batchConfig.size,
            minBatchSize = batchConfig.minSize,
            batchTimeout = batchConfig.timeout,
            parallelism = parallelConfig.workers,
            partitionCount = parallelConfig.partitions,
            enableBackpressure = backpressureConfig.enabled,
            maxPendingMessages = backpressureConfig.maxPending,
            adaptiveThreshold = backpressureConfig.adaptiveThreshold,
            useRingBuffer = bufferConfig.useRingBuffer,
            ringBufferSize = bufferConfig.ringBufferSize,
            enableWorkStealing = parallelConfig.workStealing,
            enableCpuAffinity = parallelConfig.cpuAffinity,
            queueCapacity = parallelConfig.queueCapacity,
            stealThreshold = parallelConfig.stealThreshold,
            enableAdaptiveBatching = batchConfig.adaptive,
            backpressureStrategy = backpressureConfig.strategy,
            rateLimiter = backpressureConfig.rateLimiter,
            circuitBreaker = backpressureConfig.circuitBreaker
        )
    }
}

/**
 * Batch processing configuration.
 */
@DLQHandlerDslMarker
class BatchConfig {
    var size: Int = 1000
    var minSize: Int = 10
    var timeout: Duration = Duration.ofMillis(100)
    var adaptive: Boolean = true
    
    /**
     * Sets timeout using Kotlin duration.
     */
    fun timeout(duration: KotlinDuration) {
        timeout = duration.toJavaDuration()
    }
}

/**
 * Parallel processing configuration.
 */
@DLQHandlerDslMarker
class ParallelConfig {
    var workers: Int = Runtime.getRuntime().availableProcessors()
    var partitions: Int = workers * 4
    var workStealing: Boolean = true
    var cpuAffinity: Boolean = false
    var queueCapacity: Int = 10000
    var stealThreshold: Int = 100
}

/**
 * Buffer configuration.
 */
@DLQHandlerDslMarker
class BufferConfig {
    var useRingBuffer: Boolean = true
    var ringBufferSize: Int = 65536
    
    /**
     * Sets ring buffer size using convenient units.
     */
    fun size(value: Int, unit: SizeUnit) {
        ringBufferSize = when (unit) {
            SizeUnit.BYTES -> value
            SizeUnit.KB -> value * 1024
            SizeUnit.MB -> value * 1024 * 1024
        }
    }
}

/**
 * Size units for buffer configuration.
 */
enum class SizeUnit {
    BYTES, KB, MB
}

/**
 * Backpressure configuration builder.
 */
@DLQHandlerDslMarker
class BackpressureConfigBuilder {
    var enabled: Boolean = true
    var maxPending: Int = 100_000
    var adaptiveThreshold: Double = 0.8
    
    internal var strategy: BackpressureStrategy = AdaptiveBackpressureStrategy()
    internal var rateLimiter: RateLimiter? = null
    internal var circuitBreaker: CircuitBreaker? = null
    
    /**
     * Uses adaptive backpressure strategy.
     */
    fun adaptive(block: AdaptiveStrategyBuilder.() -> Unit = {}) {
        val builder = AdaptiveStrategyBuilder().apply(block)
        strategy = AdaptiveBackpressureStrategy(
            BackpressureConfig(
                maxPendingMessages = maxPending.toLong()
            )
        )
    }
    
    /**
     * Uses threshold-based backpressure strategy.
     */
    fun threshold(block: ThresholdStrategyBuilder.() -> Unit) {
        val builder = ThresholdStrategyBuilder().apply(block)
        strategy = ThresholdBackpressureStrategy(
            BackpressureConfig(
                maxPendingMessages = maxPending.toLong(),
                maxCpuUsage = builder.cpu,
                maxMemoryUsage = builder.memory,
                maxErrorRate = builder.errorRate
            )
        )
    }
    
    /**
     * Configures rate limiting.
     */
    fun rateLimit(block: RateLimiterBuilder.() -> Unit) {
        rateLimiter = RateLimiterBuilder().apply(block).build()
    }
    
    /**
     * Configures circuit breaker.
     */
    fun circuitBreaker(block: CircuitBreakerBuilder.() -> Unit) {
        circuitBreaker = CircuitBreakerBuilder().apply(block).build()
    }
    
    /**
     * Builds the backpressure configuration.
     */
    fun build(): BackpressureConfiguration {
        return BackpressureConfiguration(
            enabled = enabled,
            maxPending = maxPending,
            adaptiveThreshold = adaptiveThreshold,
            strategy = strategy,
            rateLimiter = rateLimiter,
            circuitBreaker = circuitBreaker
        )
    }
}

/**
 * Backpressure configuration data.
 */
data class BackpressureConfiguration(
    val enabled: Boolean,
    val maxPending: Int,
    val adaptiveThreshold: Double,
    val strategy: BackpressureStrategy,
    val rateLimiter: RateLimiter?,
    val circuitBreaker: CircuitBreaker?
)

/**
 * Adaptive strategy configuration builder.
 */
@DLQHandlerDslMarker
class AdaptiveStrategyBuilder {
    var initialRate: Double = 1.0
    var minRate: Double = 0.1
    var maxRate: Double = 1.0
    var adjustmentFactor: Double = 0.1
    var windowSize: Int = 1000
    var threshold: Double = 0.8
}

/**
 * Threshold strategy configuration builder.
 */
@DLQHandlerDslMarker
class ThresholdStrategyBuilder {
    var cpu: Double = 0.8
    var memory: Double = 0.85
    var queue: Double = 0.9
    var errorRate: Double = 0.1
}

/**
 * Rate limiter configuration builder.
 */
@DLQHandlerDslMarker
class RateLimiterBuilder {
    private var type: RateLimiterType = RateLimiterType.TOKEN_BUCKET
    private var rate: Long = 10000
    private var burstSize: Long = rate * 2
    
    /**
     * Uses token bucket rate limiter.
     */
    fun tokenBucket(rate: Long, burst: Long = rate * 2) {
        type = RateLimiterType.TOKEN_BUCKET
        this.rate = rate
        this.burstSize = burst
    }
    
    /**
     * Uses sliding window rate limiter.
     */
    fun slidingWindow(rate: Long, window: Duration = Duration.ofSeconds(1)) {
        type = RateLimiterType.SLIDING_WINDOW
        this.rate = rate
    }
    
    /**
     * Builds the rate limiter.
     */
    fun build(): RateLimiter {
        return when (type) {
            RateLimiterType.TOKEN_BUCKET -> TokenBucketRateLimiter(
                initialRate = rate.toDouble(),
                burstSize = burstSize.toInt()
            )
            RateLimiterType.SLIDING_WINDOW -> SlidingWindowRateLimiter(
                rate = rate.toDouble(),
                windowSize = Duration.ofSeconds(1)
            )
        }
    }
}

/**
 * Rate limiter type enumeration.
 */
enum class RateLimiterType {
    TOKEN_BUCKET,
    SLIDING_WINDOW
}

/**
 * Circuit breaker configuration builder.
 */
@DLQHandlerDslMarker
class CircuitBreakerBuilder {
    var failureThreshold: Int = 5
    var failureRateThreshold: Double = 0.5
    var timeout: Duration = Duration.ofSeconds(30)
    var halfOpenRequests: Int = 3
    var slidingWindowSize: Int = 100
    
    /**
     * Sets timeout using Kotlin duration.
     */
    fun timeout(duration: KotlinDuration) {
        timeout = duration.toJavaDuration()
    }
    
    /**
     * Builds the circuit breaker.
     */
    fun build(): CircuitBreaker {
        return DefaultCircuitBreaker(
            CircuitBreakerConfig(
                failureThreshold = failureThreshold,
                failureRateThreshold = failureRateThreshold,
                openDuration = timeout,
                halfOpenMaxAttempts = halfOpenRequests,
                slidingWindowSize = Duration.ofMillis(slidingWindowSize.toLong())
            )
        )
    }
}



/**
 * Extension properties for convenient size creation.
 */
val Int.bytes: Pair<Int, SizeUnit> get() = this to SizeUnit.BYTES
val Int.kb: Pair<Int, SizeUnit> get() = this to SizeUnit.KB
val Int.mb: Pair<Int, SizeUnit> get() = this to SizeUnit.MB