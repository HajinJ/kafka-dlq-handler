package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import kotlinx.coroutines.flow.Flow
import java.time.Duration

/**
 * High-performance parallel processor for DLQ records.
 * 
 * This interface defines a processor that can handle records in parallel
 * using advanced techniques like work-stealing and CPU affinity for
 * optimal performance.
 * 
 * Key features:
 * - Work-stealing algorithm for load balancing
 * - Partition-aware parallel processing
 * - CPU affinity for cache optimization
 * - Zero-copy operations where possible
 * - Comprehensive metrics for monitoring
 * 
 * Performance targets:
 * - 100K+ messages/second throughput
 * - Sub-millisecond P50 latency
 * - Efficient CPU utilization
 * 
 * @since 0.1.0
 */
interface ParallelProcessor {
    
    /**
     * Processes records in parallel with automatic partitioning.
     * 
     * @param records The records to process
     * @param partitioner Function to determine partition for each record
     * @return Processing result with detailed metrics
     */
    suspend fun processParallel(
        records: List<DLQRecord>,
        partitioner: (DLQRecord) -> Int = { it.hashCode() }
    ): ParallelProcessingResult
    
    /**
     * Processes a continuous flow of records with parallel execution.
     * 
     * @param recordFlow The flow of records to process
     * @param onPartitionComplete Callback for partition completion
     */
    suspend fun processFlow(
        recordFlow: Flow<DLQRecord>,
        onPartitionComplete: (PartitionResult) -> Unit = {}
    )
    
    /**
     * Submits a single record for parallel processing.
     * 
     * @param record The record to process
     * @return A deferred result that can be awaited
     */
    suspend fun submitRecord(record: DLQRecord): ProcessingTicket
    
    /**
     * Starts the parallel processor with the given configuration.
     */
    suspend fun start()
    
    /**
     * Stops the processor gracefully, waiting for in-flight operations.
     * 
     * @param timeout Maximum time to wait for graceful shutdown
     */
    suspend fun stop(timeout: Duration = Duration.ofSeconds(30))
    
    /**
     * Gets the current parallel processing configuration.
     */
    fun getConfiguration(): ParallelProcessingConfig
    
    /**
     * Updates configuration dynamically without restart.
     */
    fun updateConfiguration(config: ParallelProcessingConfig)
    
    /**
     * Gets current processing metrics and statistics.
     */
    fun getMetrics(): ParallelProcessingMetrics
    
    /**
     * Gets the current state of all worker threads.
     */
    fun getWorkerStates(): List<WorkerState>
}

/**
 * Configuration for parallel processing.
 * 
 * @property workerCount Number of worker threads
 * @property partitionCount Number of partitions for data distribution
 * @property workStealingEnabled Enable work-stealing between workers
 * @property cpuAffinity Enable CPU affinity for workers
 * @property queueCapacity Capacity of each worker's queue
 * @property stealThreshold Queue size difference to trigger stealing
 * @property metricsInterval Interval for metrics collection
 * @since 0.1.0
 */
data class ParallelProcessingConfig(
    val workerCount: Int = Runtime.getRuntime().availableProcessors(),
    val partitionCount: Int = workerCount * 4,
    val workStealingEnabled: Boolean = true,
    val cpuAffinity: Boolean = true,
    val queueCapacity: Int = 10000,
    val stealThreshold: Int = 100,
    val metricsInterval: Duration = Duration.ofSeconds(1)
) {
    init {
        require(workerCount > 0) { "workerCount must be positive" }
        require(partitionCount >= workerCount) { "partitionCount must be >= workerCount" }
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        require(stealThreshold > 0) { "stealThreshold must be positive" }
        require(!metricsInterval.isNegative) { "metricsInterval must not be negative" }
    }
    
    companion object {
        val DEFAULT = ParallelProcessingConfig()
        
        val HIGH_THROUGHPUT = ParallelProcessingConfig(
            workerCount = Runtime.getRuntime().availableProcessors() * 2,
            partitionCount = Runtime.getRuntime().availableProcessors() * 8,
            queueCapacity = 50000,
            stealThreshold = 500
        )
        
        val LOW_LATENCY = ParallelProcessingConfig(
            workerCount = Runtime.getRuntime().availableProcessors(),
            partitionCount = Runtime.getRuntime().availableProcessors() * 2,
            queueCapacity = 1000,
            stealThreshold = 10
        )
    }
}

/**
 * Result of parallel processing operation.
 * 
 * @property totalProcessed Total records processed
 * @property successfulByPartition Success count by partition
 * @property failedByPartition Failure count by partition
 * @property duration Total processing duration
 * @property workerStats Statistics per worker
 * @since 0.1.0
 */
data class ParallelProcessingResult(
    val totalProcessed: Int,
    val successfulByPartition: Map<Int, Int>,
    val failedByPartition: Map<Int, Int>,
    val duration: Duration,
    val workerStats: List<WorkerStatistics>
) {
    val totalSuccessful: Int = successfulByPartition.values.sum()
    val totalFailed: Int = failedByPartition.values.sum()
    val throughput: Double = if (duration.toMillis() > 0) {
        (totalProcessed * 1000.0) / duration.toMillis()
    } else 0.0
    val successRate: Double = if (totalProcessed > 0) {
        (totalSuccessful.toDouble() / totalProcessed) * 100
    } else 0.0
}

/**
 * Result for a specific partition.
 * 
 * @property partitionId The partition identifier
 * @property processed Number of records processed
 * @property successful Number of successful records
 * @property failed Number of failed records
 * @property duration Processing duration for this partition
 * @since 0.1.0
 */
data class PartitionResult(
    val partitionId: Int,
    val processed: Int,
    val successful: Int,
    val failed: Int,
    val duration: Duration
)

/**
 * A ticket representing a submitted processing task.
 * 
 * @property recordId The record identifier
 * @property partitionId The assigned partition
 * @property workerId The assigned worker
 * @property submittedAt Submission timestamp
 * @since 0.1.0
 */
data class ProcessingTicket(
    val recordId: String,
    val partitionId: Int,
    val workerId: Int,
    val submittedAt: Long = System.currentTimeMillis()
) {
    /**
     * Checks if the processing is complete.
     */
    suspend fun isComplete(): Boolean = false // Will be implemented
    
    /**
     * Waits for the processing to complete and returns the result.
     */
    suspend fun await(): Result<Unit> = Result.success(Unit) // Will be implemented
}

/**
 * Current state of a worker thread.
 * 
 * @property workerId Worker identifier
 * @property state Current state
 * @property queueSize Current queue size
 * @property processedCount Total processed by this worker
 * @property cpuAffinity CPU core affinity if set
 * @property lastActivityTime Last processing activity
 * @since 0.1.0
 */
data class WorkerState(
    val workerId: Int,
    val state: State,
    val queueSize: Int,
    val processedCount: Long,
    val cpuAffinity: Int? = null,
    val lastActivityTime: Long
) {
    enum class State {
        IDLE, PROCESSING, STEALING, STOPPED
    }
}

/**
 * Statistics for a worker thread.
 * 
 * @property workerId Worker identifier
 * @property processed Total records processed
 * @property successful Successfully processed records
 * @property failed Failed records
 * @property stolen Records stolen from other workers
 * @property averageLatency Average processing latency
 * @since 0.1.0
 */
data class WorkerStatistics(
    val workerId: Int,
    val processed: Long,
    val successful: Long,
    val failed: Long,
    val stolen: Long,
    val averageLatency: Duration
)

/**
 * Comprehensive metrics for parallel processing.
 * 
 * @property totalProcessed Total records processed across all workers
 * @property totalSuccessful Total successful records
 * @property totalFailed Total failed records
 * @property totalStolen Total stolen tasks (work-stealing)
 * @property averageThroughput Average throughput per worker
 * @property peakThroughput Peak throughput achieved
 * @property workerUtilization CPU utilization per worker
 * @property queueDepths Current queue depths per worker
 * @property stealingRate Rate of work-stealing operations
 * @since 0.1.0
 */
data class ParallelProcessingMetrics(
    val totalProcessed: Long,
    val totalSuccessful: Long,
    val totalFailed: Long,
    val totalStolen: Long,
    val averageThroughput: Double,
    val peakThroughput: Double,
    val workerUtilization: Map<Int, Double>,
    val queueDepths: Map<Int, Int>,
    val stealingRate: Double
)