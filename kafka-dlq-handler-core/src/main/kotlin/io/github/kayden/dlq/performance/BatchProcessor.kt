package io.github.kayden.dlq.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.storage.DLQStorage
import io.github.kayden.dlq.storage.BatchStorageResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource

/**
 * High-performance batch processor for DLQ records.
 * 
 * This processor collects records into batches and processes them
 * efficiently using configurable batch sizes and timeouts. It uses
 * RingBuffer for internal queuing and supports dynamic batch sizing
 * based on processing performance.
 * 
 * @property config Batch processing configuration
 * @property storage Storage implementation for persisting batches
 * @property dispatcher Coroutine dispatcher for async processing
 * @property scope Coroutine scope for async processing
 * 
 * @since 0.2.0
 */
class BatchProcessor(
    private val config: BatchProcessingConfig,
    private val storage: DLQStorage,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob()),
    private val timeSource: TimeSource = TimeSource.Monotonic
) {
    private val ringBuffer = RingBuffer(config.bufferSize)
    private val isRunning = AtomicBoolean(false)
    private val processedCount = AtomicLong(0)
    private val failedCount = AtomicLong(0)
    
    private val metricsChannel = Channel<BatchMetrics>(Channel.UNLIMITED)
    private var processingJob: Job? = null
    
    /**
     * Starts the batch processor.
     * 
     * @throws IllegalStateException if already running
     */
    fun start() {
        if (!isRunning.compareAndSet(false, true)) {
            throw IllegalStateException("BatchProcessor is already running")
        }
        
        processingJob = scope.launch {
            supervisorScope {
                // Start worker coroutines
                val workers = (1..config.workerCount).map { workerId ->
                    launch { processWorker(workerId) }
                }
                
                // Start metrics collector if enabled
                if (config.enableMetrics) {
                    launch { collectMetrics() }
                }
            }
        }
    }
    
    /**
     * Stops the batch processor gracefully.
     * 
     * @param timeout Maximum time to wait for graceful shutdown
     */
    suspend fun stop(timeout: Duration = Duration.ofSeconds(30)) {
        isRunning.set(false)
        
        withTimeoutOrNull(timeout.toMillis()) {
            processingJob?.join()  // Wait for completion instead of canceling
        }
        
        processingJob?.cancel()
    }
    
    /**
     * Submits a record for batch processing.
     * 
     * @param record The DLQ record to process
     * @return true if accepted, false if buffer is full
     */
    fun submit(record: DLQRecord): Boolean {
        if (!isRunning.get()) {
            throw IllegalStateException("BatchProcessor is not running")
        }
        
        return ringBuffer.offer(record)
    }
    
    /**
     * Submits multiple records for batch processing.
     * 
     * @param records The DLQ records to process
     * @return Number of records accepted
     */
    fun submitAll(records: List<DLQRecord>): Int {
        var accepted = 0
        for (record in records) {
            if (submit(record)) {
                accepted++
            } else {
                break
            }
        }
        return accepted
    }
    
    private suspend fun processWorker(workerId: Int) {
        val batch = mutableListOf<DLQRecord>()
        var lastFlushMark = timeSource.markNow()
        var consecutiveEmptyPolls = 0
        
        while (true) {
            // During shutdown, process more aggressively
            val maxPollAttempts = if (isRunning.get()) 1 else 10
            var record: DLQRecord? = null
            
            repeat(maxPollAttempts) {
                val polled = ringBuffer.poll()
                if (polled != null) {
                    record = polled
                    return@repeat
                }
            }
            
            record?.let {
                batch.add(it)
                consecutiveEmptyPolls = 0
            } ?: run {
                consecutiveEmptyPolls++
            }
            
            val shouldFlushBySize = batch.size >= config.batchSize
            val shouldFlushByTimeout = batch.isNotEmpty() && 
                lastFlushMark.elapsedNow().inWholeMilliseconds >= config.batchTimeout.toMillis()
            
            if (shouldFlushBySize || shouldFlushByTimeout) {
                if (batch.isNotEmpty()) {
                    processBatch(batch.toList(), workerId)
                    batch.clear()
                    lastFlushMark = timeSource.markNow()
                }
            }
            
            // Handle empty poll
            if (record == null) {
                if (isRunning.get()) {
                    if (batch.isEmpty()) {
                        // No record available and no pending batch, wait a bit
                        delay(10)
                    } else {
                        // Check timeout again after a short delay
                        val remainingTime = config.batchTimeout.toMillis() - lastFlushMark.elapsedNow().inWholeMilliseconds
                        if (remainingTime > 0) {
                            delay(minOf(remainingTime, 10))
                        }
                    }
                } else {
                    // Shutting down
                    if (batch.isNotEmpty()) {
                        // Flush any pending batch immediately during shutdown
                        processBatch(batch.toList(), workerId)
                        batch.clear()
                        lastFlushMark = timeSource.markNow()
                    }
                    
                    // Check if we can exit
                    // We need many consecutive empty polls to be sure the buffer is empty
                    // This handles race conditions and timing issues
                    if (consecutiveEmptyPolls >= 50) {
                        // Triple-check the buffer is really empty
                        var finalChecks = 0
                        var isEmpty = true
                        repeat(5) {
                            if (ringBuffer.size() > 0) {
                                isEmpty = false
                                return@repeat
                            }
                            delay(10)
                            finalChecks++
                        }
                        
                        if (isEmpty && finalChecks == 5) {
                            break
                        }
                        // Reset counter if buffer not empty
                        consecutiveEmptyPolls = 0
                    } else {
                        // Small delay during shutdown to allow buffer to fill
                        delay(5)
                    }
                }
            }
        }
        
        // Final check for any remaining records
        if (batch.isNotEmpty()) {
            processBatch(batch.toList(), workerId)
        }
    }
    
    private suspend fun processBatch(batch: List<DLQRecord>, workerId: Int) {
        val startTime = Instant.now()
        
        try {
            val result = storage.saveBatch(batch)
            
            processedCount.addAndGet(result.successful.size.toLong())
            failedCount.addAndGet(result.failed.size.toLong())
            
            val metrics = BatchMetrics(
                workerId = workerId,
                batchSize = batch.size,
                successCount = result.successful.size,
                failureCount = result.failed.size,
                processingTime = result.duration,
                timestamp = Instant.now()
            )
            
            metricsChannel.trySend(metrics)
            
        } catch (e: Exception) {
            failedCount.addAndGet(batch.size.toLong())
            
            val metrics = BatchMetrics(
                workerId = workerId,
                batchSize = batch.size,
                successCount = 0,
                failureCount = batch.size,
                processingTime = Duration.between(startTime, Instant.now()),
                timestamp = Instant.now(),
                error = e.message
            )
            
            metricsChannel.trySend(metrics)
        }
    }
    
    private suspend fun collectMetrics() {
        for (metrics in metricsChannel) {
            // Log or export metrics
            logMetrics(metrics)
        }
    }
    
    private fun logMetrics(metrics: BatchMetrics) {
        val throughput = if (metrics.processingTime.toMillis() > 0) {
            metrics.batchSize * 1000.0 / metrics.processingTime.toMillis()
        } else {
            0.0
        }
        
        println("Batch[${metrics.workerId}]: ${metrics.successCount}/${metrics.batchSize} " +
                "in ${metrics.processingTime.toMillis()}ms (${throughput.toInt()} msg/s)")
    }
    
    /**
     * Returns current processing statistics.
     */
    fun getStats(): ProcessingStats {
        return ProcessingStats(
            processedCount = processedCount.get(),
            failedCount = failedCount.get(),
            bufferUtilization = ringBuffer.utilization(),
            isRunning = isRunning.get()
        )
    }
}

/**
 * Batch processing configuration.
 * 
 * @property batchSize Maximum number of records per batch
 * @property batchTimeout Maximum time to wait before flushing a batch
 * @property bufferSize Size of the internal ring buffer (must be power of 2)
 * @property workerCount Number of concurrent workers
 * @property enableMetrics Whether to collect processing metrics
 */
data class BatchProcessingConfig(
    val batchSize: Int = 100,
    val batchTimeout: Duration = Duration.ofMillis(100),
    val bufferSize: Int = 8192,
    val workerCount: Int = Runtime.getRuntime().availableProcessors(),
    val enableMetrics: Boolean = true
) {
    init {
        require(batchSize > 0) { "Batch size must be positive" }
        require(bufferSize > 0 && bufferSize and (bufferSize - 1) == 0) { 
            "Buffer size must be a power of 2" 
        }
        require(workerCount > 0) { "Worker count must be positive" }
    }
    
    companion object {
        val DEFAULT = BatchProcessingConfig()
        
        val HIGH_THROUGHPUT = BatchProcessingConfig(
            batchSize = 1000,
            batchTimeout = Duration.ofMillis(50),
            bufferSize = 65536,
            workerCount = Runtime.getRuntime().availableProcessors() * 2
        )
        
        val LOW_LATENCY = BatchProcessingConfig(
            batchSize = 10,
            batchTimeout = Duration.ofMillis(10),
            bufferSize = 4096,
            workerCount = Runtime.getRuntime().availableProcessors()
        )
    }
}

/**
 * Batch processing metrics.
 */
data class BatchMetrics(
    val workerId: Int,
    val batchSize: Int,
    val successCount: Int,
    val failureCount: Int,
    val processingTime: Duration,
    val timestamp: Instant,
    val error: String? = null
)

/**
 * Processing statistics.
 */
data class ProcessingStats(
    val processedCount: Long,
    val failedCount: Long,
    val bufferUtilization: Double,
    val isRunning: Boolean
)