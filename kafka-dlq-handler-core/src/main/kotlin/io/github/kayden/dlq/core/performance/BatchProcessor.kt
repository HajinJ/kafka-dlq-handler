package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import kotlinx.coroutines.flow.Flow
import java.time.Duration

/**
 * High-performance batch processor for DLQ records.
 * 
 * This interface defines a processor that can handle records in batches,
 * optimizing for throughput while maintaining low latency.
 * 
 * Key features:
 * - Adaptive batch sizing based on load
 * - Non-blocking processing with coroutines
 * - Metrics collection for monitoring
 * - Integration with RingBuffer for high throughput
 * 
 * @since 0.1.0
 */
interface BatchProcessor {
    
    /**
     * Processes a batch of DLQ records.
     * 
     * @param records The records to process
     * @return The processing result containing success/failure counts
     */
    suspend fun processBatch(records: List<DLQRecord>): BatchResult
    
    /**
     * Processes records from a flow in batches.
     * 
     * This method consumes records from the flow and processes them
     * in optimally sized batches based on current throughput.
     * 
     * @param recordFlow The flow of records to process
     * @param onBatchComplete Callback invoked after each batch completes
     */
    suspend fun processFlow(
        recordFlow: Flow<DLQRecord>,
        onBatchComplete: (BatchResult) -> Unit = {}
    )
    
    /**
     * Starts the batch processor.
     * 
     * This method should be called before processing any records.
     * It initializes internal resources and starts background tasks.
     */
    suspend fun start()
    
    /**
     * Stops the batch processor gracefully.
     * 
     * This method ensures all pending batches are processed
     * before shutting down.
     * 
     * @param timeout Maximum time to wait for graceful shutdown
     */
    suspend fun stop(timeout: Duration = Duration.ofSeconds(30))
    
    /**
     * Gets the current batch configuration.
     * 
     * @return The current batch configuration
     */
    fun getConfiguration(): BatchConfiguration
    
    /**
     * Updates the batch configuration dynamically.
     * 
     * @param config The new configuration to apply
     */
    fun updateConfiguration(config: BatchConfiguration)
    
    /**
     * Gets the current processing metrics.
     * 
     * @return Current metrics snapshot
     */
    fun getMetrics(): BatchProcessingMetrics
}

/**
 * Configuration for batch processing.
 * 
 * @property minBatchSize Minimum number of records per batch
 * @property maxBatchSize Maximum number of records per batch
 * @property maxBatchDelay Maximum time to wait before processing a partial batch
 * @property adaptiveSizing Whether to use adaptive batch sizing
 * @property parallelism Number of parallel processing coroutines
 * @since 0.1.0
 */
data class BatchConfiguration(
    val minBatchSize: Int = 10,
    val maxBatchSize: Int = 1000,
    val maxBatchDelay: Duration = Duration.ofMillis(100),
    val adaptiveSizing: Boolean = true,
    val parallelism: Int = Runtime.getRuntime().availableProcessors()
) {
    init {
        require(minBatchSize > 0) { "minBatchSize must be positive" }
        require(maxBatchSize >= minBatchSize) { "maxBatchSize must be >= minBatchSize" }
        require(!maxBatchDelay.isNegative) { "maxBatchDelay must not be negative" }
        require(parallelism > 0) { "parallelism must be positive" }
    }
    
    companion object {
        /**
         * Default configuration optimized for throughput.
         */
        val DEFAULT = BatchConfiguration()
        
        /**
         * Configuration optimized for low latency.
         */
        val LOW_LATENCY = BatchConfiguration(
            minBatchSize = 1,
            maxBatchSize = 100,
            maxBatchDelay = Duration.ofMillis(10),
            adaptiveSizing = false
        )
        
        /**
         * Configuration optimized for high throughput.
         */
        val HIGH_THROUGHPUT = BatchConfiguration(
            minBatchSize = 100,
            maxBatchSize = 10000,
            maxBatchDelay = Duration.ofMillis(500),
            adaptiveSizing = true
        )
    }
}

/**
 * Result of batch processing.
 * 
 * @property successful Number of successfully processed records
 * @property failed Number of failed records
 * @property duration Time taken to process the batch
 * @property batchSize Total size of the batch
 * @property throughput Records processed per second
 * @since 0.1.0
 */
data class BatchResult(
    val successful: Int,
    val failed: Int,
    val duration: Duration,
    val batchSize: Int = successful + failed,
    val throughput: Double = if (duration.toMillis() > 0) {
        (successful * 1000.0) / duration.toMillis()
    } else {
        0.0
    }
) {
    /**
     * Success rate as a percentage (0-100).
     */
    val successRate: Double = if (batchSize > 0) {
        (successful.toDouble() / batchSize) * 100
    } else {
        0.0
    }
    
    /**
     * Whether all records were processed successfully.
     */
    val isFullySuccessful: Boolean = failed == 0 && successful > 0
}

/**
 * Metrics for batch processing performance.
 * 
 * @property totalProcessed Total number of records processed
 * @property totalSuccessful Total number of successful records
 * @property totalFailed Total number of failed records
 * @property totalBatches Total number of batches processed
 * @property averageBatchSize Average size of processed batches
 * @property averageThroughput Average throughput in records/second
 * @property currentThroughput Current throughput (last minute)
 * @property peakThroughput Peak throughput achieved
 * @property averageLatency Average processing latency per batch
 * @property p99Latency 99th percentile latency
 * @since 0.1.0
 */
data class BatchProcessingMetrics(
    val totalProcessed: Long,
    val totalSuccessful: Long,
    val totalFailed: Long,
    val totalBatches: Long,
    val averageBatchSize: Double,
    val averageThroughput: Double,
    val currentThroughput: Double,
    val peakThroughput: Double,
    val averageLatency: Duration,
    val p99Latency: Duration
) {
    /**
     * Overall success rate as a percentage.
     */
    val overallSuccessRate: Double = if (totalProcessed > 0) {
        (totalSuccessful.toDouble() / totalProcessed) * 100
    } else {
        0.0
    }
}