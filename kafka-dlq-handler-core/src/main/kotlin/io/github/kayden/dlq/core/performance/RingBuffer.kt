package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord

/**
 * High-performance ring buffer for DLQ message processing.
 * 
 * This interface defines a lock-free ring buffer based on the Disruptor pattern.
 * It supports single producer multiple consumer (SPMC) scenarios and provides
 * high throughput message passing with minimal latency.
 * 
 * Key features:
 * - Lock-free implementation for high concurrency
 * - Zero garbage collection under steady state
 * - Support for batching operations
 * - Configurable wait strategies
 * 
 * @since 0.1.0
 */
interface RingBuffer {
    
    /**
     * The capacity of the ring buffer (must be a power of 2).
     */
    val capacity: Int
    
    /**
     * Publishes a single DLQ record to the buffer.
     * 
     * @param record The DLQ record to publish
     * @return The sequence number of the published record, or -1 if the buffer is full
     */
    fun publish(record: DLQRecord): Long
    
    /**
     * Publishes multiple DLQ records as a batch.
     * 
     * @param records The list of DLQ records to publish
     * @return The sequence number of the last published record, or -1 if the buffer is full
     */
    fun publishBatch(records: List<DLQRecord>): Long
    
    /**
     * Gets a record at the specified sequence number.
     * 
     * @param sequence The sequence number
     * @return The DLQ record at the specified sequence, or null if not available
     */
    fun get(sequence: Long): DLQRecord?
    
    /**
     * Gets the current producer sequence number.
     * 
     * @return The current sequence number
     */
    fun getCursor(): Long
    
    /**
     * Gets the highest published sequence number.
     * 
     * @return The highest published sequence
     */
    fun getHighestPublishedSequence(): Long
    
    /**
     * Checks if the buffer has available capacity.
     * 
     * @param requiredCapacity The number of slots required
     * @return true if the buffer has the required capacity
     */
    fun hasAvailableCapacity(requiredCapacity: Int): Boolean
    
    /**
     * Gets the remaining capacity of the buffer.
     * 
     * @return The number of available slots
     */
    fun remainingCapacity(): Long
    
    /**
     * Creates a new sequence barrier for consumers.
     * 
     * @param dependentSequences Sequences this barrier depends on
     * @return A new sequence barrier
     */
    fun newBarrier(vararg dependentSequences: Sequence): SequenceBarrier
}