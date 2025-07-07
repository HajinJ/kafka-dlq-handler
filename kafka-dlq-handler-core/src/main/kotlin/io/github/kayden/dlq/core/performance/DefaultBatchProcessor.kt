package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.storage.DLQStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

/**
 * Default implementation of BatchProcessor with adaptive batch sizing.
 * 
 * This implementation provides:
 * - Adaptive batch sizing based on throughput
 * - Non-blocking processing with coroutines
 * - Integration with RingBuffer for high throughput
 * - Comprehensive metrics collection
 * 
 * @property storage The storage to save processed records
 * @property recordHandler Handler function for processing individual records
 * @property config Initial batch configuration
 * @property scope Coroutine scope for background tasks
 * @since 0.1.0
 */
class DefaultBatchProcessor(
    private val storage: DLQStorage,
    private val recordHandler: suspend (DLQRecord) -> Result<Unit>,
    private var config: BatchConfiguration = BatchConfiguration.DEFAULT,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : BatchProcessor {
    
    private val isRunning = AtomicBoolean(false)
    private val channel = Channel<DLQRecord>(Channel.UNLIMITED)
    
    // Metrics tracking
    private val totalProcessed = AtomicLong(0)
    private val totalSuccessful = AtomicLong(0)
    private val totalFailed = AtomicLong(0)
    private val totalBatches = AtomicLong(0)
    
    // Throughput tracking
    private val recentThroughputs = ConcurrentLinkedQueue<ThroughputSample>()
    private var peakThroughput = 0.0
    
    // Latency tracking
    private val latencyHistory = ConcurrentLinkedQueue<Long>()
    private val maxLatencyHistorySize = 1000
    
    // Adaptive sizing state
    private var currentBatchSize = config.minBatchSize
    private var lastAdjustmentTime = Instant.now()
    private val adjustmentInterval = Duration.ofSeconds(5)
    
    private lateinit var processingJob: Job
    
    override suspend fun processBatch(records: List<DLQRecord>): BatchResult {
        if (records.isEmpty()) {
            return BatchResult(0, 0, Duration.ZERO)
        }
        
        val startTime = System.currentTimeMillis()
        var successful = 0
        var failed = 0
        
        // Process records in parallel
        coroutineScope {
            records.chunked(records.size / config.parallelism + 1).map { chunk ->
                async {
                    chunk.forEach { record ->
                        try {
                            recordHandler(record).fold(
                                onSuccess = { 
                                    storage.save(record)
                                    successful++
                                },
                                onFailure = { 
                                    failed++ 
                                }
                            )
                        } catch (e: Exception) {
                            failed++
                        }
                    }
                }
            }.awaitAll()
        }
        
        val duration = Duration.ofMillis(System.currentTimeMillis() - startTime)
        
        // Update metrics
        totalProcessed.addAndGet(records.size.toLong())
        totalSuccessful.addAndGet(successful.toLong())
        totalFailed.addAndGet(failed.toLong())
        totalBatches.incrementAndGet()
        
        // Track latency
        trackLatency(duration.toMillis())
        
        // Track throughput
        val throughput = if (duration.toMillis() > 0) {
            (records.size * 1000.0) / duration.toMillis()
        } else {
            0.0
        }
        trackThroughput(throughput)
        
        return BatchResult(successful, failed, duration)
    }
    
    override suspend fun processFlow(
        recordFlow: Flow<DLQRecord>,
        onBatchComplete: (BatchResult) -> Unit
    ) {
        recordFlow.collect { record ->
            channel.send(record)
        }
    }
    
    override suspend fun start() {
        if (isRunning.compareAndSet(false, true)) {
            processingJob = scope.launch {
                processRecords()
            }
            
            // Start metrics collection
            scope.launch {
                collectMetrics()
            }
        }
    }
    
    override suspend fun stop(timeout: Duration) {
        if (isRunning.compareAndSet(true, false)) {
            channel.close()
            
            withTimeoutOrNull(timeout.toMillis()) {
                processingJob.join()
            }
            
            scope.cancel()
        }
    }
    
    override fun getConfiguration(): BatchConfiguration = config
    
    override fun updateConfiguration(config: BatchConfiguration) {
        this.config = config
        this.currentBatchSize = config.minBatchSize
    }
    
    override fun getMetrics(): BatchProcessingMetrics {
        val avgBatchSize = if (totalBatches.get() > 0) {
            totalProcessed.get().toDouble() / totalBatches.get()
        } else {
            0.0
        }
        
        val avgThroughput = calculateAverageThroughput()
        val currentThroughput = calculateCurrentThroughput()
        
        val latencies = latencyHistory.toList().sorted()
        val avgLatency = if (latencies.isNotEmpty()) {
            Duration.ofMillis(latencies.average().toLong())
        } else {
            Duration.ZERO
        }
        
        val p99Latency = if (latencies.isNotEmpty()) {
            val p99Index = (latencies.size * 0.99).toInt()
            Duration.ofMillis(latencies[min(p99Index, latencies.size - 1)])
        } else {
            Duration.ZERO
        }
        
        return BatchProcessingMetrics(
            totalProcessed = totalProcessed.get(),
            totalSuccessful = totalSuccessful.get(),
            totalFailed = totalFailed.get(),
            totalBatches = totalBatches.get(),
            averageBatchSize = avgBatchSize,
            averageThroughput = avgThroughput,
            currentThroughput = currentThroughput,
            peakThroughput = peakThroughput,
            averageLatency = avgLatency,
            p99Latency = p99Latency
        )
    }
    
    private suspend fun processRecords() {
        while (isRunning.get()) {
            val batch = mutableListOf<DLQRecord>()
            val deadline = System.currentTimeMillis() + config.maxBatchDelay.toMillis()
            
            // Collect batch
            while (batch.size < currentBatchSize && System.currentTimeMillis() < deadline) {
                val timeRemaining = deadline - System.currentTimeMillis()
                if (timeRemaining > 0) {
                    withTimeoutOrNull(timeRemaining) {
                        channel.receiveCatching().getOrNull()?.let { batch.add(it) }
                    }
                }
            }
            
            // Process batch if not empty
            if (batch.isNotEmpty()) {
                val result = processBatch(batch)
                
                // Adaptive batch sizing
                if (config.adaptiveSizing) {
                    adjustBatchSize(result)
                }
            } else {
                // No records available, sleep briefly
                delay(10)
            }
        }
    }
    
    private fun adjustBatchSize(result: BatchResult) {
        val now = Instant.now()
        if (Duration.between(lastAdjustmentTime, now) < adjustmentInterval) {
            return
        }
        
        lastAdjustmentTime = now
        val throughput = result.throughput
        
        // Simple adaptive algorithm
        when {
            throughput > peakThroughput * 0.9 && currentBatchSize < config.maxBatchSize -> {
                // Increase batch size if near peak performance
                currentBatchSize = min(currentBatchSize * 2, config.maxBatchSize)
            }
            result.duration > config.maxBatchDelay && currentBatchSize > config.minBatchSize -> {
                // Decrease batch size if taking too long
                currentBatchSize = max(currentBatchSize / 2, config.minBatchSize)
            }
        }
    }
    
    private fun trackLatency(latencyMs: Long) {
        latencyHistory.offer(latencyMs)
        while (latencyHistory.size > maxLatencyHistorySize) {
            latencyHistory.poll()
        }
    }
    
    private fun trackThroughput(throughput: Double) {
        val sample = ThroughputSample(Instant.now(), throughput)
        recentThroughputs.offer(sample)
        
        // Keep only last minute of samples
        val cutoff = Instant.now().minusSeconds(60)
        recentThroughputs.removeIf { it.timestamp < cutoff }
        
        // Update peak
        if (throughput > peakThroughput) {
            peakThroughput = throughput
        }
    }
    
    private suspend fun collectMetrics() {
        while (isRunning.get()) {
            delay(1000) // Collect metrics every second
            // Metrics are collected passively through processing
        }
    }
    
    private fun calculateAverageThroughput(): Double {
        val samples = recentThroughputs.toList()
        return if (samples.isNotEmpty()) {
            samples.map { it.throughput }.average()
        } else {
            0.0
        }
    }
    
    private fun calculateCurrentThroughput(): Double {
        val recentCutoff = Instant.now().minusSeconds(10)
        val recentSamples = recentThroughputs.filter { it.timestamp > recentCutoff }
        return if (recentSamples.isNotEmpty()) {
            recentSamples.map { it.throughput }.average()
        } else {
            0.0
        }
    }
    
    private data class ThroughputSample(
        val timestamp: Instant,
        val throughput: Double
    )
}

/**
 * Builder for creating DefaultBatchProcessor instances.
 * 
 * @since 0.1.0
 */
class BatchProcessorBuilder {
    private lateinit var storage: DLQStorage
    private lateinit var recordHandler: suspend (DLQRecord) -> Result<Unit>
    private var config = BatchConfiguration.DEFAULT
    private var scope: CoroutineScope? = null
    
    fun storage(storage: DLQStorage) = apply {
        this.storage = storage
    }
    
    fun recordHandler(handler: suspend (DLQRecord) -> Result<Unit>) = apply {
        this.recordHandler = handler
    }
    
    fun configuration(config: BatchConfiguration) = apply {
        this.config = config
    }
    
    fun scope(scope: CoroutineScope) = apply {
        this.scope = scope
    }
    
    fun build(): DefaultBatchProcessor {
        return DefaultBatchProcessor(
            storage = storage,
            recordHandler = recordHandler,
            config = config,
            scope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
        )
    }
}

/**
 * Extension function to create a batch processor with DSL.
 * 
 * @param block Configuration block
 * @return Configured batch processor
 */
fun batchProcessor(block: BatchProcessorBuilder.() -> Unit): DefaultBatchProcessor {
    return BatchProcessorBuilder().apply(block).build()
}