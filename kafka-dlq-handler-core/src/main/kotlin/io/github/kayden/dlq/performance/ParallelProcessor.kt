package io.github.kayden.dlq.performance

import io.github.kayden.dlq.core.model.DLQRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for parallel processing
 */
data class ParallelProcessorConfig(
    val parallelism: Int = Runtime.getRuntime().availableProcessors(),
    val channelCapacity: Int = 1000,
    val workStealingEnabled: Boolean = true,
    val partitionAware: Boolean = true,
    val workerIdleTimeout: Duration = 5.seconds,
    val metricsEnabled: Boolean = true
) {
    init {
        require(parallelism > 0) { "Parallelism must be positive" }
        require(channelCapacity > 0) { "Channel capacity must be positive" }
    }
}

/**
 * Interface for parallel message processing
 */
interface ParallelProcessor {
    suspend fun process(records: List<DLQRecord>): ProcessingResult
    suspend fun processStream(records: ReceiveChannel<DLQRecord>): ProcessingResult
    fun shutdown()
    fun getMetrics(): ParallelProcessorMetrics
}

/**
 * Result of parallel processing
 */
data class ProcessingResult(
    val successCount: Long,
    val failureCount: Long,
    val processingTime: Duration,
    val throughput: Double = successCount.toDouble() / processingTime.inWholeSeconds.coerceAtLeast(1)
)

/**
 * Metrics for parallel processor monitoring
 */
data class ParallelProcessorMetrics(
    val totalProcessed: Long,
    val successCount: Long,
    val failureCount: Long,
    val activeWorkers: Int,
    val idleWorkers: Int,
    val queuedMessages: Int,
    val averageProcessingTime: Duration
)

/**
 * Message processor function type
 */
typealias MessageProcessor = suspend (DLQRecord) -> Boolean

/**
 * Coroutine-based parallel processor implementation
 */
class CoroutineParallelProcessor(
    private val config: ParallelProcessorConfig = ParallelProcessorConfig(),
    private val processor: MessageProcessor,
    private val errorHandler: suspend (DLQRecord, Throwable) -> Unit = { _, _ -> }
) : ParallelProcessor {
    
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )
    
    // Metrics
    private val totalProcessed = AtomicLong(0)
    private val successCount = AtomicLong(0)
    private val failureCount = AtomicLong(0)
    private val processingTimes = ConcurrentHashMap<String, Long>()
    
    // Worker management
    private val workers = mutableListOf<Job>()
    private val activeWorkers = AtomicLong(0)
    
    // Partition-aware channels
    private val partitionChannels = ConcurrentHashMap<Int, Channel<DLQRecord>>()
    private val globalChannel = Channel<DLQRecord>(config.channelCapacity)
    
    init {
        startWorkers()
    }
    
    override suspend fun process(records: List<DLQRecord>): ProcessingResult {
        val startTime = System.currentTimeMillis()
        
        coroutineScope {
            if (config.partitionAware) {
                // Group by partition for ordered processing
                records.groupBy { it.originalPartition }.forEach { (partition, partitionRecords) ->
                    val channel = getOrCreatePartitionChannel(partition)
                    launch {
                        partitionRecords.forEach { record ->
                            channel.send(record)
                        }
                    }
                }
            } else {
                // Send all to global channel
                launch {
                    records.forEach { record ->
                        globalChannel.send(record)
                    }
                }
            }
        }
        
        // Wait for processing to complete
        delay(100) // Allow workers to process
        
        val endTime = System.currentTimeMillis()
        val duration = (endTime - startTime).milliseconds
        
        return ProcessingResult(
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            processingTime = duration
        )
    }
    
    override suspend fun processStream(records: ReceiveChannel<DLQRecord>): ProcessingResult {
        val startTime = System.currentTimeMillis()
        
        coroutineScope {
            launch {
                for (record in records) {
                    if (config.partitionAware) {
                        val channel = getOrCreatePartitionChannel(record.originalPartition)
                        channel.send(record)
                    } else {
                        globalChannel.send(record)
                    }
                }
            }
        }
        
        val endTime = System.currentTimeMillis()
        val duration = (endTime - startTime).milliseconds
        
        return ProcessingResult(
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            processingTime = duration
        )
    }
    
    override fun shutdown() {
        scope.cancel()
        workers.forEach { it.cancel() }
        partitionChannels.values.forEach { it.close() }
        globalChannel.close()
    }
    
    override fun getMetrics(): ParallelProcessorMetrics {
        val avgProcessingTime = if (processingTimes.isNotEmpty()) {
            processingTimes.values.average().toLong().milliseconds
        } else {
            Duration.ZERO
        }
        
        return ParallelProcessorMetrics(
            totalProcessed = totalProcessed.get(),
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            activeWorkers = activeWorkers.get().toInt(),
            idleWorkers = config.parallelism - activeWorkers.get().toInt(),
            queuedMessages = partitionChannels.values.sumOf { it.toString().length } + globalChannel.toString().length,
            averageProcessingTime = avgProcessingTime
        )
    }
    
    private fun startWorkers() {
        repeat(config.parallelism) { workerId ->
            val worker = scope.launch {
                workerLoop(workerId)
            }
            workers.add(worker)
        }
    }
    
    private suspend fun workerLoop(workerId: Int) {
        while (scope.isActive) {
            try {
                // Try to steal work from partition channels first
                if (config.workStealingEnabled && config.partitionAware) {
                    val stolenRecord = tryStealWork()
                    if (stolenRecord != null) {
                        processRecord(stolenRecord, workerId)
                        continue
                    }
                }
                
                // Process from global channel
                val result = withTimeoutOrNull(config.workerIdleTimeout) {
                    val record = globalChannel.receive()
                    processRecord(record, workerId)
                }
                if (result == null) continue
                
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                // Log error but continue processing
            }
        }
    }
    
    private suspend fun processRecord(record: DLQRecord, workerId: Int) {
        activeWorkers.incrementAndGet()
        val startTime = System.currentTimeMillis()
        
        try {
            val success = processor(record)
            if (success) {
                successCount.incrementAndGet()
            } else {
                failureCount.incrementAndGet()
            }
        } catch (e: Exception) {
            failureCount.incrementAndGet()
            errorHandler(record, e)
        } finally {
            totalProcessed.incrementAndGet()
            activeWorkers.decrementAndGet()
            
            val processingTime = System.currentTimeMillis() - startTime
            processingTimes["worker-$workerId"] = processingTime
        }
    }
    
    private suspend fun tryStealWork(): DLQRecord? {
        // Simple work stealing: try to get from least loaded partition
        val channels = partitionChannels.entries.sortedBy { it.value.toString().length }
        
        for ((_, channel) in channels) {
            val record = channel.tryReceive().getOrNull()
            if (record != null) return record
        }
        
        return null
    }
    
    private fun getOrCreatePartitionChannel(partition: Int): Channel<DLQRecord> {
        return partitionChannels.computeIfAbsent(partition) {
            Channel(config.channelCapacity / config.parallelism)
        }
    }
}

/**
 * Factory for creating ParallelProcessor instances
 */
object ParallelProcessorFactory {
    fun create(
        config: ParallelProcessorConfig = ParallelProcessorConfig(),
        processor: MessageProcessor
    ): ParallelProcessor {
        return CoroutineParallelProcessor(config, processor)
    }
    
    /**
     * Create a processor optimized for CPU-bound work
     */
    fun createCpuBound(
        processor: MessageProcessor,
        parallelism: Int = Runtime.getRuntime().availableProcessors()
    ): ParallelProcessor {
        val config = ParallelProcessorConfig(
            parallelism = parallelism,
            channelCapacity = parallelism * 100,
            workStealingEnabled = true,
            partitionAware = true
        )
        return CoroutineParallelProcessor(config, processor)
    }
    
    /**
     * Create a processor optimized for IO-bound work
     */
    fun createIoBound(
        processor: MessageProcessor,
        parallelism: Int = Runtime.getRuntime().availableProcessors() * 2
    ): ParallelProcessor {
        val config = ParallelProcessorConfig(
            parallelism = parallelism,
            channelCapacity = parallelism * 200,
            workStealingEnabled = true,
            partitionAware = false
        )
        return CoroutineParallelProcessor(config, processor)
    }
}