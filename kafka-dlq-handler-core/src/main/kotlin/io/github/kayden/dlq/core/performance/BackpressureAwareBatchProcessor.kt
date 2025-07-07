package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.storage.DLQStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * BatchProcessor implementation with integrated backpressure handling.
 * 
 * This processor monitors system load and applies backpressure strategies
 * to prevent system overload while maintaining optimal throughput.
 * 
 * @property storage The storage to save processed records
 * @property recordHandler Handler function for processing individual records
 * @property backpressureStrategy Strategy for handling backpressure
 * @property rateLimiter Optional rate limiter
 * @property circuitBreaker Optional circuit breaker
 * @property config Batch processing configuration
 * @property scope Coroutine scope for background tasks
 * @since 0.1.0
 */
class BackpressureAwareBatchProcessor(
    private val storage: DLQStorage,
    private val recordHandler: suspend (DLQRecord) -> Result<Unit>,
    private val backpressureStrategy: BackpressureStrategy = AdaptiveBackpressureStrategy(),
    private val rateLimiter: RateLimiter? = null,
    private val circuitBreaker: CircuitBreaker? = null,
    config: BatchConfiguration = BatchConfiguration.DEFAULT,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : BatchProcessor {
    
    // Delegate to DefaultBatchProcessor for core functionality
    private val delegateProcessor = DefaultBatchProcessor(
        storage = storage,
        recordHandler = { record -> processWithBackpressure(record) },
        config = config,
        scope = scope
    )
    
    // Metrics
    private val pendingMessages = AtomicLong(0)
    private val processingMessages = AtomicLong(0)
    private val acceptedMessages = AtomicLong(0)
    private val rejectedMessages = AtomicLong(0)
    
    private val memoryBean = ManagementFactory.getMemoryMXBean()
    private val osBean = ManagementFactory.getOperatingSystemMXBean()
    
    /**
     * Publishes a record if backpressure allows.
     * 
     * @param record The record to publish
     * @return true if accepted, false if rejected due to backpressure
     */
    suspend fun tryPublish(record: DLQRecord): Boolean {
        val loadMetrics = getCurrentLoadMetrics()
        
        // Check backpressure strategy
        if (!backpressureStrategy.shouldAccept(loadMetrics)) {
            backpressureStrategy.onRejected()
            rejectedMessages.incrementAndGet()
            return false
        }
        
        // Check rate limiter
        if (rateLimiter != null && !rateLimiter.tryAcquire()) {
            rejectedMessages.incrementAndGet()
            return false
        }
        
        // Check circuit breaker
        if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
            rejectedMessages.incrementAndGet()
            return false
        }
        
        // Accept the message
        backpressureStrategy.onAccepted()
        acceptedMessages.incrementAndGet()
        pendingMessages.incrementAndGet()
        
        return true
    }
    
    override suspend fun processBatch(records: List<DLQRecord>): BatchResult {
        val accepted = mutableListOf<DLQRecord>()
        
        // Filter records through backpressure
        records.forEach { record ->
            if (tryPublish(record).also { if (!it) delay(1) }) {
                accepted.add(record)
            }
        }
        
        // Process accepted records
        return if (accepted.isNotEmpty()) {
            delegateProcessor.processBatch(accepted)
        } else {
            BatchResult(0, 0, Duration.ZERO)
        }
    }
    
    override suspend fun processFlow(
        recordFlow: Flow<DLQRecord>,
        onBatchComplete: (BatchResult) -> Unit
    ) {
        recordFlow.collect { record ->
            // Retry with exponential backoff if rejected
            var retries = 0
            while (!tryPublish(record) && retries < 3) {
                delay((1L shl retries) * 100) // 100ms, 200ms, 400ms
                retries++
            }
        }
    }
    
    override suspend fun start() {
        delegateProcessor.start()
        
        // Start monitoring task
        GlobalScope.launch {
            monitorSystemLoad()
        }
    }
    
    override suspend fun stop(timeout: Duration) {
        delegateProcessor.stop(timeout)
    }
    
    override fun getConfiguration(): BatchConfiguration = delegateProcessor.getConfiguration()
    
    override fun updateConfiguration(config: BatchConfiguration) {
        delegateProcessor.updateConfiguration(config)
    }
    
    override fun getMetrics(): BatchProcessingMetrics {
        val delegateMetrics = delegateProcessor.getMetrics()
        
        // Add backpressure-specific metrics
        return delegateMetrics.copy(
            totalProcessed = acceptedMessages.get() + rejectedMessages.get()
        )
    }
    
    /**
     * Gets backpressure-specific metrics.
     * 
     * @return Backpressure metrics
     */
    fun getBackpressureMetrics(): BackpressureAwareMetrics {
        return BackpressureAwareMetrics(
            batchMetrics = getMetrics(),
            backpressureMetrics = backpressureStrategy.getMetrics(),
            rateLimiterMetrics = rateLimiter?.getMetrics(),
            circuitBreakerMetrics = circuitBreaker?.getMetrics(),
            pendingMessages = pendingMessages.get(),
            acceptedMessages = acceptedMessages.get(),
            rejectedMessages = rejectedMessages.get()
        )
    }
    
    private suspend fun processWithBackpressure(record: DLQRecord): Result<Unit> {
        processingMessages.incrementAndGet()
        pendingMessages.decrementAndGet()
        
        val startTime = Instant.now()
        return try {
            val result = recordHandler(record)
            val duration = Duration.between(startTime, Instant.now())
            
            // Record result in backpressure components
            if (result.isSuccess) {
                backpressureStrategy.onCompleted(true, duration)
                circuitBreaker?.recordSuccess()
            } else {
                backpressureStrategy.onCompleted(false, duration)
                circuitBreaker?.recordFailure()
            }
            
            result
        } finally {
            processingMessages.decrementAndGet()
        }
    }
    
    private fun getCurrentLoadMetrics(): LoadMetrics {
        val heapUsage = memoryBean.heapMemoryUsage
        val memoryPercent = (heapUsage.used.toDouble() / heapUsage.max) * 100
        
        // Use system load average as CPU metric (0-100 scale)
        val cpuPercent = osBean.systemLoadAverage * 10.0 // Rough approximation
        
        val errorRate = delegateProcessor.getMetrics().let { metrics ->
            if (metrics.totalProcessed > 0) {
                metrics.totalFailed.toDouble() / metrics.totalProcessed
            } else {
                0.0
            }
        }
        
        return LoadMetrics(
            pendingMessages = pendingMessages.get(),
            processingMessages = processingMessages.get(),
            memoryUsagePercent = memoryPercent,
            cpuUsagePercent = cpuPercent.coerceIn(0.0, 100.0),
            errorRate = errorRate,
            averageProcessingTime = delegateProcessor.getMetrics().averageLatency
        )
    }
    
    private suspend fun monitorSystemLoad() {
        while (true) {
            delay(1000) // Monitor every second
            
            val loadMetrics = getCurrentLoadMetrics()
            
            // Log warnings if system is under stress
            if (loadMetrics.isHighLoad()) {
                // In production, this would log to monitoring system
            }
        }
    }
}

/**
 * Combined metrics for backpressure-aware batch processing.
 * 
 * @property batchMetrics Standard batch processing metrics
 * @property backpressureMetrics Backpressure strategy metrics
 * @property rateLimiterMetrics Optional rate limiter metrics
 * @property circuitBreakerMetrics Optional circuit breaker metrics
 * @property pendingMessages Current pending messages
 * @property acceptedMessages Total accepted messages
 * @property rejectedMessages Total rejected messages
 * @since 0.1.0
 */
data class BackpressureAwareMetrics(
    val batchMetrics: BatchProcessingMetrics,
    val backpressureMetrics: BackpressureMetrics,
    val rateLimiterMetrics: RateLimiterMetrics?,
    val circuitBreakerMetrics: CircuitBreakerMetrics?,
    val pendingMessages: Long,
    val acceptedMessages: Long,
    val rejectedMessages: Long
) {
    /**
     * Overall acceptance rate considering all components.
     */
    val overallAcceptanceRate: Double = if (acceptedMessages + rejectedMessages > 0) {
        (acceptedMessages.toDouble() / (acceptedMessages + rejectedMessages)) * 100
    } else {
        100.0
    }
}

/**
 * Builder for creating BackpressureAwareBatchProcessor instances.
 * 
 * @since 0.1.0
 */
class BackpressureAwareBatchProcessorBuilder {
    private lateinit var storage: DLQStorage
    private lateinit var recordHandler: suspend (DLQRecord) -> Result<Unit>
    private var backpressureStrategy: BackpressureStrategy = AdaptiveBackpressureStrategy()
    private var rateLimiter: RateLimiter? = null
    private var circuitBreaker: CircuitBreaker? = null
    private var config = BatchConfiguration.DEFAULT
    private var scope: CoroutineScope? = null
    
    fun storage(storage: DLQStorage) = apply {
        this.storage = storage
    }
    
    fun recordHandler(handler: suspend (DLQRecord) -> Result<Unit>) = apply {
        this.recordHandler = handler
    }
    
    fun backpressureStrategy(strategy: BackpressureStrategy) = apply {
        this.backpressureStrategy = strategy
    }
    
    fun rateLimiter(limiter: RateLimiter) = apply {
        this.rateLimiter = limiter
    }
    
    fun circuitBreaker(breaker: CircuitBreaker) = apply {
        this.circuitBreaker = breaker
    }
    
    fun configuration(config: BatchConfiguration) = apply {
        this.config = config
    }
    
    fun scope(scope: CoroutineScope) = apply {
        this.scope = scope
    }
    
    fun build(): BackpressureAwareBatchProcessor {
        return BackpressureAwareBatchProcessor(
            storage = storage,
            recordHandler = recordHandler,
            backpressureStrategy = backpressureStrategy,
            rateLimiter = rateLimiter,
            circuitBreaker = circuitBreaker,
            config = config,
            scope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
        )
    }
}

/**
 * Extension function to create a BackpressureAwareBatchProcessor with DSL.
 * 
 * @param block Configuration block
 * @return Configured processor
 */
fun backpressureAwareBatchProcessor(
    block: BackpressureAwareBatchProcessorBuilder.() -> Unit
): BackpressureAwareBatchProcessor {
    return BackpressureAwareBatchProcessorBuilder().apply(block).build()
}