package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.storage.DLQStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * BatchProcessor implementation that integrates with RingBuffer for maximum performance.
 * 
 * This implementation uses RingBuffer as the underlying queue mechanism,
 * providing lock-free publishing and efficient batch consumption.
 * 
 * @property storage The storage to save processed records
 * @property recordHandler Handler function for processing individual records
 * @property ringBuffer The ring buffer for queuing records
 * @property config Batch processing configuration
 * @property scope Coroutine scope for background tasks
 * @since 0.1.0
 */
class RingBufferBatchProcessor(
    private val storage: DLQStorage,
    private val recordHandler: suspend (DLQRecord) -> Result<Unit>,
    private val ringBuffer: RingBufferImpl,
    private var config: BatchConfiguration = BatchConfiguration.DEFAULT,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : BatchProcessor {
    
    private val isRunning = AtomicBoolean(false)
    private val consumerSequence = SequenceImpl(-1L)
    private val processingJobs = mutableListOf<Job>()
    
    // Use the default processor for actual batch processing logic
    private val delegateProcessor = DefaultBatchProcessor(
        storage = storage,
        recordHandler = recordHandler,
        config = config,
        scope = scope
    )
    
    init {
        // Register consumer sequence with ring buffer
        ringBuffer.addGatingSequence(consumerSequence)
    }
    
    /**
     * Publishes a record to the ring buffer.
     * 
     * @param record The record to publish
     * @return The sequence number, or -1 if buffer is full
     */
    fun publish(record: DLQRecord): Long {
        return ringBuffer.publish(record)
    }
    
    /**
     * Publishes multiple records to the ring buffer.
     * 
     * @param records The records to publish
     * @return The last sequence number, or -1 if buffer is full
     */
    fun publishBatch(records: List<DLQRecord>): Long {
        return ringBuffer.publishBatch(records)
    }
    
    override suspend fun processBatch(records: List<DLQRecord>): BatchResult {
        return delegateProcessor.processBatch(records)
    }
    
    override suspend fun processFlow(
        recordFlow: Flow<DLQRecord>,
        onBatchComplete: (BatchResult) -> Unit
    ) {
        // Collect from flow and publish to ring buffer
        recordFlow.collect { record ->
            while (publish(record) == -1L) {
                // Buffer is full, wait a bit
                delay(1)
            }
        }
    }
    
    override suspend fun start() {
        if (isRunning.compareAndSet(false, true)) {
            // Start the delegate processor
            delegateProcessor.start()
            
            // Start consumer coroutines
            repeat(config.parallelism) { workerId ->
                val job = scope.launch {
                    consumeFromRingBuffer(workerId)
                }
                processingJobs.add(job)
            }
        }
    }
    
    override suspend fun stop(timeout: Duration) {
        if (isRunning.compareAndSet(true, false)) {
            // Wait for all jobs to complete
            withTimeoutOrNull(timeout.toMillis()) {
                processingJobs.forEach { it.join() }
            }
            
            // Stop the delegate processor
            delegateProcessor.stop(timeout)
            
            // Remove consumer sequence from ring buffer
            ringBuffer.removeGatingSequence(consumerSequence)
            
            scope.cancel()
        }
    }
    
    override fun getConfiguration(): BatchConfiguration = config
    
    override fun updateConfiguration(config: BatchConfiguration) {
        this.config = config
        delegateProcessor.updateConfiguration(config)
    }
    
    override fun getMetrics(): BatchProcessingMetrics = delegateProcessor.getMetrics()
    
    private suspend fun consumeFromRingBuffer(workerId: Int) {
        val barrier = ringBuffer.newBarrier(consumerSequence)
        var nextSequence = consumerSequence.get() + 1
        val batch = mutableListOf<DLQRecord>()
        var batchDeadline = System.currentTimeMillis() + config.maxBatchDelay.toMillis()
        
        while (isRunning.get()) {
            try {
                // Wait for next available sequence
                val availableSequence = withTimeoutOrNull(10) {
                    barrier.waitFor(nextSequence)
                } ?: nextSequence - 1
                
                // Collect available records up to batch size
                while (nextSequence <= availableSequence && batch.size < config.maxBatchSize) {
                    ringBuffer.get(nextSequence)?.let { record ->
                        batch.add(record)
                    }
                    nextSequence++
                }
                
                // Process batch if it's full or deadline reached
                val shouldProcess = batch.size >= config.minBatchSize || 
                    (batch.isNotEmpty() && System.currentTimeMillis() >= batchDeadline)
                
                if (shouldProcess) {
                    processBatch(batch)
                    batch.clear()
                    batchDeadline = System.currentTimeMillis() + config.maxBatchDelay.toMillis()
                }
                
                // Update consumer sequence
                consumerSequence.set(nextSequence - 1)
                
            } catch (e: AlertException) {
                // Barrier was alerted, likely shutting down
                break
            } catch (e: Exception) {
                // Log error and continue
                delay(10)
            }
        }
        
        // Process any remaining records
        if (batch.isNotEmpty()) {
            processBatch(batch)
        }
    }
}

/**
 * Builder for creating RingBufferBatchProcessor instances.
 * 
 * @since 0.1.0
 */
class RingBufferBatchProcessorBuilder {
    private lateinit var storage: DLQStorage
    private lateinit var recordHandler: suspend (DLQRecord) -> Result<Unit>
    private var ringBufferSize = 65536
    private var waitStrategy: WaitStrategy = YieldingWaitStrategy()
    private var config = BatchConfiguration.DEFAULT
    private var scope: CoroutineScope? = null
    
    fun storage(storage: DLQStorage) = apply {
        this.storage = storage
    }
    
    fun recordHandler(handler: suspend (DLQRecord) -> Result<Unit>) = apply {
        this.recordHandler = handler
    }
    
    fun ringBufferSize(size: Int) = apply {
        require(size > 0 && (size and (size - 1)) == 0) {
            "Ring buffer size must be a power of 2"
        }
        this.ringBufferSize = size
    }
    
    fun waitStrategy(strategy: WaitStrategy) = apply {
        this.waitStrategy = strategy
    }
    
    fun configuration(config: BatchConfiguration) = apply {
        this.config = config
    }
    
    fun scope(scope: CoroutineScope) = apply {
        this.scope = scope
    }
    
    fun build(): RingBufferBatchProcessor {
        val ringBuffer = RingBufferImpl(ringBufferSize, waitStrategy)
        return RingBufferBatchProcessor(
            storage = storage,
            recordHandler = recordHandler,
            ringBuffer = ringBuffer,
            config = config,
            scope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
        )
    }
}

/**
 * Extension function to create a RingBufferBatchProcessor with DSL.
 * 
 * @param block Configuration block
 * @return Configured processor
 */
fun ringBufferBatchProcessor(block: RingBufferBatchProcessorBuilder.() -> Unit): RingBufferBatchProcessor {
    return RingBufferBatchProcessorBuilder().apply(block).build()
}