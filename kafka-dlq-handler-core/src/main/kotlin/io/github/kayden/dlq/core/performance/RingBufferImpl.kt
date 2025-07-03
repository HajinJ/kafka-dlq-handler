package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.min

/**
 * High-performance ring buffer implementation for DLQ message processing.
 * 
 * This implementation is based on the Disruptor pattern and provides:
 * - Lock-free publishing for single producer
 * - Zero garbage collection under steady state
 * - Cache-friendly memory layout
 * - Support for batch operations
 * 
 * @property capacity The size of the ring buffer (must be power of 2)
 * @property waitStrategy The wait strategy for consumers
 * @throws IllegalArgumentException if capacity is not a power of 2
 * @since 0.1.0
 */
class RingBufferImpl(
    override val capacity: Int,
    private val waitStrategy: WaitStrategy = YieldingWaitStrategy()
) : RingBuffer {
    
    init {
        require(capacity > 0) { "Capacity must be greater than 0" }
        require(capacity and (capacity - 1) == 0) { "Capacity must be a power of 2" }
    }
    
    private val indexMask = capacity - 1
    private val entries = AtomicReferenceArray<DLQRecord?>(capacity)
    
    // Sequences for tracking positions
    private val cursor = SequenceImpl(INITIAL_CURSOR_VALUE)
    private val gatingSequenceCache = SequenceImpl(INITIAL_CURSOR_VALUE)
    private val gatingSequences = mutableListOf<Sequence>()
    
    companion object {
        const val INITIAL_CURSOR_VALUE = -1L
    }
    
    override fun publish(record: DLQRecord): Long {
        val sequence = cursor.incrementAndGet()
        translateAndPublish(record, sequence)
        return sequence
    }
    
    override fun publishBatch(records: List<DLQRecord>): Long {
        if (records.isEmpty()) return cursor.get()
        
        val batchSize = records.size
        val firstSequence = cursor.get() + 1
        val lastSequence = cursor.addAndGet(batchSize.toLong())
        
        // Check if we have enough space
        val wrapPoint = lastSequence - capacity + 1
        val cachedGatingSequence = gatingSequenceCache.get()
        
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > lastSequence) {
            val minSequence = getMinimumSequence(gatingSequences.toTypedArray(), lastSequence)
            gatingSequenceCache.set(minSequence)
            
            if (wrapPoint > minSequence) {
                // Buffer is full, rollback
                cursor.set(firstSequence - 1)
                return -1
            }
        }
        
        // Publish all records
        records.forEachIndexed { index, record ->
            val sequence = firstSequence + index
            translateAndPublish(record, sequence)
        }
        
        return lastSequence
    }
    
    override fun get(sequence: Long): DLQRecord? {
        return entries.get(sequence.toInt() and indexMask)
    }
    
    override fun getCursor(): Long = cursor.get()
    
    override fun getHighestPublishedSequence(): Long = cursor.get()
    
    override fun hasAvailableCapacity(requiredCapacity: Int): Boolean {
        val nextSequence = cursor.get() + requiredCapacity
        val wrapPoint = nextSequence - capacity + 1
        val cachedGatingSequence = gatingSequenceCache.get()
        
        if (wrapPoint > cachedGatingSequence) {
            val minSequence = getMinimumSequence(gatingSequences.toTypedArray(), nextSequence)
            gatingSequenceCache.set(minSequence)
            return wrapPoint <= minSequence
        }
        
        return true
    }
    
    override fun remainingCapacity(): Long {
        val consumed = getMinimumSequence(gatingSequences.toTypedArray(), cursor.get())
        val produced = cursor.get()
        return capacity - (produced - consumed)
    }
    
    override fun newBarrier(vararg dependentSequences: Sequence): SequenceBarrier {
        return SequenceBarrierImpl(dependentSequences, waitStrategy, cursor)
    }
    
    /**
     * Adds a gating sequence to track consumer progress.
     * 
     * @param sequence The sequence to add
     */
    fun addGatingSequence(sequence: Sequence) {
        gatingSequences.add(sequence)
    }
    
    /**
     * Removes a gating sequence.
     * 
     * @param sequence The sequence to remove
     * @return true if the sequence was removed
     */
    fun removeGatingSequence(sequence: Sequence): Boolean {
        return gatingSequences.remove(sequence)
    }
    
    private fun translateAndPublish(record: DLQRecord, sequence: Long) {
        entries.set(sequence.toInt() and indexMask, record)
    }
    
    private fun getMinimumSequence(sequences: Array<out Sequence>, minimum: Long): Long {
        var minSequence = minimum
        for (sequence in sequences) {
            val value = sequence.get()
            minSequence = min(minSequence, value)
        }
        return minSequence
    }
}

/**
 * Builder for creating RingBuffer instances with fluent API.
 * 
 * @since 0.1.0
 */
class RingBufferBuilder {
    private var capacity = 1024
    private var waitStrategy: WaitStrategy = YieldingWaitStrategy()
    
    /**
     * Sets the capacity of the ring buffer.
     * Must be a power of 2.
     * 
     * @param size The capacity
     * @return This builder
     */
    fun capacity(size: Int) = apply {
        require(size > 0) { "Capacity must be greater than 0" }
        require(size and (size - 1) == 0) { "Capacity must be a power of 2" }
        this.capacity = size
    }
    
    /**
     * Sets the wait strategy.
     * 
     * @param strategy The wait strategy to use
     * @return This builder
     */
    fun waitStrategy(strategy: WaitStrategy) = apply {
        this.waitStrategy = strategy
    }
    
    /**
     * Uses busy spin wait strategy for lowest latency.
     * 
     * @return This builder
     */
    fun withBusySpinWaitStrategy() = apply {
        this.waitStrategy = BusySpinWaitStrategy()
    }
    
    /**
     * Uses yielding wait strategy for balanced performance.
     * 
     * @return This builder
     */
    fun withYieldingWaitStrategy() = apply {
        this.waitStrategy = YieldingWaitStrategy()
    }
    
    /**
     * Uses sleeping wait strategy for low CPU usage.
     * 
     * @param sleepTimeNs Sleep time in nanoseconds
     * @return This builder
     */
    fun withSleepingWaitStrategy(sleepTimeNs: Long = 200_000L) = apply {
        this.waitStrategy = SleepingWaitStrategy(sleepTimeNs)
    }
    
    /**
     * Uses blocking wait strategy with condition variables.
     * 
     * @return This builder
     */
    fun withBlockingWaitStrategy() = apply {
        this.waitStrategy = BlockingWaitStrategy()
    }
    
    /**
     * Builds the ring buffer instance.
     * 
     * @return The configured ring buffer
     */
    fun build(): RingBufferImpl {
        return RingBufferImpl(capacity, waitStrategy)
    }
}

/**
 * Extension function to create a ring buffer with DSL.
 * 
 * @param block Configuration block
 * @return Configured ring buffer
 */
fun ringBuffer(block: RingBufferBuilder.() -> Unit): RingBufferImpl {
    return RingBufferBuilder().apply(block).build()
}