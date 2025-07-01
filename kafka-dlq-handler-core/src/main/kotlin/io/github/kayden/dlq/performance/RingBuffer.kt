package io.github.kayden.dlq.performance

import io.github.kayden.dlq.core.model.DLQRecord
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * High-performance lock-free ring buffer for DLQ message queuing.
 * 
 * This implementation uses a single-producer, multi-consumer pattern
 * optimized for high-throughput scenarios. The buffer size must be
 * a power of 2 for efficient modulo operations using bit masking.
 * 
 * @property size The size of the ring buffer (must be power of 2)
 * @throws IllegalArgumentException if size is not a power of 2
 * 
 * @since 0.2.0
 */
class RingBuffer(size: Int = 65536) {
    init {
        require(size > 0) { "Size must be positive" }
        require(size and (size - 1) == 0) { "Size must be a power of 2" }
    }
    
    private val buffer = AtomicReferenceArray<DLQRecord?>(size)
    private val mask = size - 1
    
    // Separate head and tail for false sharing prevention
    @Volatile
    private var headCache = 0L
    private val head = AtomicLong(0)
    
    @Volatile
    private var tailCache = 0L
    private val tail = AtomicLong(0)
    
    /**
     * Offers a record to the ring buffer.
     * 
     * @param record The DLQ record to add
     * @return true if the record was added, false if buffer is full
     */
    fun offer(record: DLQRecord): Boolean {
        val currentTail = tail.get()
        val wrapPoint = currentTail - buffer.length()
        
        if (headCache <= wrapPoint) {
            headCache = head.get()
            if (headCache <= wrapPoint) {
                return false // Buffer full
            }
        }
        
        val index = (currentTail and mask.toLong()).toInt()
        buffer.set(index, record)
        tail.lazySet(currentTail + 1)
        
        return true
    }
    
    /**
     * Polls a record from the ring buffer.
     * 
     * @return The oldest record in the buffer, or null if empty
     */
    fun poll(): DLQRecord? {
        val currentHead = head.get()
        
        if (currentHead >= tailCache) {
            tailCache = tail.get()
            if (currentHead >= tailCache) {
                return null // Buffer empty
            }
        }
        
        val index = (currentHead and mask.toLong()).toInt()
        val record = buffer.get(index)
        
        if (record == null) {
            return null
        }
        
        buffer.set(index, null)
        head.lazySet(currentHead + 1)
        
        return record
    }
    
    /**
     * Returns the current number of elements in the buffer.
     * 
     * Note: This is an approximation in concurrent scenarios.
     */
    fun size(): Int {
        val currentTail = tail.get()
        val currentHead = head.get()
        val size = currentTail - currentHead
        
        return when {
            size < 0 -> 0
            size > buffer.length() -> buffer.length()
            else -> size.toInt()
        }
    }
    
    /**
     * Checks if the buffer is empty.
     * 
     * @return true if buffer contains no elements
     */
    fun isEmpty(): Boolean = head.get() >= tail.get()
    
    /**
     * Checks if the buffer is full.
     * 
     * @return true if buffer cannot accept more elements
     */
    fun isFull(): Boolean = size() >= buffer.length()
    
    /**
     * Returns the capacity of the ring buffer.
     */
    fun capacity(): Int = buffer.length()
    
    /**
     * Clears all elements from the buffer.
     * 
     * Note: This operation is not thread-safe and should only
     * be called when no other threads are accessing the buffer.
     */
    fun clear() {
        while (poll() != null) {
            // Drain the buffer
        }
    }
    
    /**
     * Returns the current utilization percentage of the buffer.
     * 
     * @return utilization percentage (0.0 to 100.0)
     */
    fun utilization(): Double {
        val currentSize = size()
        return (currentSize.toDouble() / buffer.length()) * 100.0
    }
}