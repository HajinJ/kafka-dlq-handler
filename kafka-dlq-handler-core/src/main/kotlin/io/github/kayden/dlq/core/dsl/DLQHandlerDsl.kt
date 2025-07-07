package io.github.kayden.dlq.core.dsl

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.performance.*
import io.github.kayden.dlq.core.storage.DLQStorage
import io.github.kayden.dlq.core.storage.InMemoryStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Duration

/**
 * DSL marker for DLQ handler configuration.
 * Ensures type safety in nested DSL blocks.
 * 
 * @since 0.1.0
 */
@DslMarker
annotation class DLQHandlerDslMarker

/**
 * Creates a new DLQ handler with DSL configuration.
 * 
 * Example:
 * ```kotlin
 * val handler = dlqHandler {
 *     performance {
 *         batchSize = 1000
 *         parallelism = 8
 *     }
 *     storage {
 *         inMemory {
 *             maxSize = 100_000
 *         }
 *     }
 * }
 * ```
 * 
 * @param block Configuration block
 * @return Configured DLQ handler
 * @since 0.1.0
 */
fun dlqHandler(block: DLQHandlerBuilder.() -> Unit): DLQHandler {
    return DLQHandlerBuilder().apply(block).build()
}

/**
 * Main DLQ handler interface.
 * 
 * @since 0.1.0
 */
interface DLQHandler {
    /**
     * Starts the handler.
     */
    suspend fun start()
    
    /**
     * Stops the handler gracefully.
     */
    suspend fun stop(timeout: Duration = Duration.ofSeconds(30))
    
    /**
     * Processes a single record.
     */
    suspend fun process(record: DLQRecord): Result<Unit>
    
    /**
     * Processes multiple records.
     */
    suspend fun processBatch(records: List<DLQRecord>): BatchResult
    
    /**
     * Gets current metrics.
     */
    fun getMetrics(): DLQHandlerMetrics
}

/**
 * Builder for DLQ handler configuration.
 * 
 * @since 0.1.0
 */
@DLQHandlerDslMarker
class DLQHandlerBuilder {
    private var storage: DLQStorage = InMemoryStorage()
    private var performanceConfig = PerformanceConfiguration()
    private var processingConfig = ProcessingConfiguration()
    private var errorHandler: ErrorHandler = DefaultErrorHandler()
    private var metricsCollector: MetricsCollector = DefaultMetricsCollector()
    private var scope: CoroutineScope? = null
    
    /**
     * Configures performance settings.
     */
    fun performance(block: PerformanceConfigBuilder.() -> Unit) {
        performanceConfig = PerformanceConfigBuilder().apply(block).build()
    }
    
    /**
     * Configures storage backend.
     */
    fun storage(block: StorageBuilder.() -> Unit) {
        storage = StorageBuilder().apply(block).build()
    }
    
    /**
     * Configures processing behavior.
     */
    fun processing(block: ProcessingConfigBuilder.() -> Unit) {
        processingConfig = ProcessingConfigBuilder().apply(block).build()
    }
    
    /**
     * Configures error handling.
     */
    fun errorHandling(block: ErrorHandlingBuilder.() -> Unit) {
        errorHandler = ErrorHandlingBuilder().apply(block).build()
    }
    
    /**
     * Configures metrics collection.
     */
    fun metrics(block: MetricsBuilder.() -> Unit) {
        metricsCollector = MetricsBuilder().apply(block).build()
    }
    
    /**
     * Sets custom coroutine scope.
     */
    fun withScope(scope: CoroutineScope) {
        this.scope = scope
    }
    
    /**
     * Builds the DLQ handler.
     */
    fun build(): DLQHandler {
        val finalScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        return when (performanceConfig.processingMode) {
            ProcessingMode.BATCH -> createBatchHandler(finalScope)
            ProcessingMode.PARALLEL -> createParallelHandler(finalScope)
            ProcessingMode.HYBRID -> createHybridHandler(finalScope)
        }
    }
    
    private fun createBatchHandler(scope: CoroutineScope): DLQHandler {
        val batchProcessor = if (performanceConfig.enableBackpressure) {
            BackpressureAwareBatchProcessor(
                storage = storage,
                recordHandler = processingConfig.recordProcessor ?: { Result.success(Unit) },
                backpressureStrategy = performanceConfig.backpressureStrategy,
                rateLimiter = performanceConfig.rateLimiter,
                circuitBreaker = performanceConfig.circuitBreaker,
                config = BatchConfiguration(
                    minBatchSize = performanceConfig.batchSize / 10,
                    maxBatchSize = performanceConfig.batchSize,
                    maxBatchDelay = performanceConfig.batchTimeout,
                    adaptiveSizing = true
                ),
                scope = scope
            )
        } else {
            createBaseBatchProcessor(scope)
        }
        
        return BatchDLQHandler(
            processor = batchProcessor,
            storage = storage,
            errorHandler = errorHandler,
            metricsCollector = metricsCollector,
            config = processingConfig
        )
    }
    
    private fun createParallelHandler(scope: CoroutineScope): DLQHandler {
        val parallelProcessor = WorkStealingParallelProcessor(
            storage = storage,
            recordHandler = createRecordHandler(),
            config = ParallelProcessingConfig(
                workerCount = performanceConfig.parallelism,
                partitionCount = performanceConfig.partitionCount,
                workStealingEnabled = performanceConfig.enableWorkStealing,
                cpuAffinity = performanceConfig.enableCpuAffinity,
                queueCapacity = performanceConfig.queueCapacity,
                stealThreshold = performanceConfig.stealThreshold
            ),
            scope = scope
        )
        
        return ParallelDLQHandler(
            processor = parallelProcessor,
            storage = storage,
            errorHandler = errorHandler,
            metricsCollector = metricsCollector,
            config = processingConfig
        )
    }
    
    private fun createHybridHandler(scope: CoroutineScope): DLQHandler {
        // Hybrid mode combines batch and parallel processing
        return HybridDLQHandler(
            batchProcessor = createBaseBatchProcessor(scope),
            parallelProcessor = WorkStealingParallelProcessor(
                storage = storage,
                recordHandler = createRecordHandler(),
                config = ParallelProcessingConfig(
                    workerCount = performanceConfig.parallelism / 2,
                    workStealingEnabled = true
                ),
                scope = scope
            ),
            storage = storage,
            errorHandler = errorHandler,
            metricsCollector = metricsCollector,
            config = processingConfig
        )
    }
    
    private fun createBaseBatchProcessor(scope: CoroutineScope): BatchProcessor {
        return if (performanceConfig.useRingBuffer) {
            RingBufferBatchProcessor(
                ringBuffer = RingBufferImpl(performanceConfig.ringBufferSize),
                storage = storage,
                recordHandler = createRecordHandler(),
                config = BatchConfiguration(
                    minBatchSize = performanceConfig.minBatchSize,
                    maxBatchSize = performanceConfig.batchSize,
                    maxBatchDelay = performanceConfig.batchTimeout,
                    adaptiveSizing = performanceConfig.enableAdaptiveBatching,
                    parallelism = performanceConfig.parallelism
                ),
                scope = scope
            )
        } else {
            DefaultBatchProcessor(
                storage = storage,
                recordHandler = createRecordHandler(),
                config = BatchConfiguration(
                    minBatchSize = performanceConfig.minBatchSize,
                    maxBatchSize = performanceConfig.batchSize,
                    maxBatchDelay = performanceConfig.batchTimeout,
                    adaptiveSizing = performanceConfig.enableAdaptiveBatching,
                    parallelism = performanceConfig.parallelism
                ),
                scope = scope
            )
        }
    }
    
    private fun createRecordHandler(): suspend (DLQRecord) -> Result<Unit> {
        return { record ->
            processingConfig.recordProcessor?.invoke(record)
                ?: Result.success(Unit)
        }
    }
}

/**
 * Performance configuration data class.
 */
data class PerformanceConfiguration(
    val processingMode: ProcessingMode = ProcessingMode.BATCH,
    val batchSize: Int = 1000,
    val minBatchSize: Int = 10,
    val batchTimeout: Duration = Duration.ofMillis(100),
    val parallelism: Int = Runtime.getRuntime().availableProcessors(),
    val partitionCount: Int = parallelism * 4,
    val enableBackpressure: Boolean = true,
    val maxPendingMessages: Int = 100_000,
    val adaptiveThreshold: Double = 0.8,
    val useRingBuffer: Boolean = true,
    val ringBufferSize: Int = 65536,
    val enableWorkStealing: Boolean = true,
    val enableCpuAffinity: Boolean = false,
    val queueCapacity: Int = 10000,
    val stealThreshold: Int = 100,
    val enableAdaptiveBatching: Boolean = true,
    val backpressureStrategy: BackpressureStrategy = AdaptiveBackpressureStrategy(),
    val rateLimiter: RateLimiter? = null,
    val circuitBreaker: CircuitBreaker? = null
)

/**
 * Processing configuration data class.
 */
data class ProcessingConfiguration(
    val maxRetries: Int = 3,
    val retryBackoff: Duration = Duration.ofSeconds(1),
    val processingTimeout: Duration = Duration.ofMinutes(5),
    val recordProcessor: (suspend (DLQRecord) -> Result<Unit>)? = null,
    val enableDeduplication: Boolean = false,
    val deduplicationWindow: Duration = Duration.ofMinutes(5)
)

/**
 * Processing mode enumeration.
 */
enum class ProcessingMode {
    BATCH,      // Traditional batch processing
    PARALLEL,   // Work-stealing parallel processing
    HYBRID      // Combination of batch and parallel
}

/**
 * Error handler interface.
 */
interface ErrorHandler {
    suspend fun handleError(record: DLQRecord, error: Throwable)
    suspend fun handleBatchError(records: List<DLQRecord>, error: Throwable)
}

/**
 * Default error handler implementation.
 */
class DefaultErrorHandler : ErrorHandler {
    override suspend fun handleError(record: DLQRecord, error: Throwable) {
        // Default: log and continue
    }
    
    override suspend fun handleBatchError(records: List<DLQRecord>, error: Throwable) {
        // Default: log and continue
    }
}

/**
 * Metrics collector interface.
 */
interface MetricsCollector {
    fun recordProcessed(count: Long, duration: Duration)
    fun recordError(count: Long, errorType: String)
    fun recordBackpressure(dropped: Long)
    fun getMetrics(): DLQHandlerMetrics
}

/**
 * Default metrics collector implementation.
 */
class DefaultMetricsCollector : MetricsCollector {
    private var totalProcessed = 0L
    private var totalErrors = 0L
    private var totalDropped = 0L
    
    override fun recordProcessed(count: Long, duration: Duration) {
        totalProcessed += count
    }
    
    override fun recordError(count: Long, errorType: String) {
        totalErrors += count
    }
    
    override fun recordBackpressure(dropped: Long) {
        totalDropped += dropped
    }
    
    override fun getMetrics(): DLQHandlerMetrics {
        return DLQHandlerMetrics(
            totalProcessed = totalProcessed,
            totalErrors = totalErrors,
            totalDropped = totalDropped
        )
    }
}

/**
 * DLQ handler metrics.
 */
data class DLQHandlerMetrics(
    val totalProcessed: Long,
    val totalErrors: Long,
    val totalDropped: Long
)