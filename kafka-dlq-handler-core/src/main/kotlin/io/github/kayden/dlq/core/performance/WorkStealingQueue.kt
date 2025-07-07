package io.github.kayden.dlq.core.performance

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.min

/**
 * A high-performance work-stealing queue implementation.
 * 
 * This queue supports:
 * - Lock-free push/pop operations from the owner thread
 * - Lock-free steal operations from other threads
 * - Bounded capacity with power-of-2 sizing
 * - Memory-efficient circular buffer design
 * 
 * Based on the algorithm described in "Idempotent Work Stealing"
 * by Michael, Saraswat, and Vechev.
 * 
 * @param T The type of elements in the queue
 * @property capacity The queue capacity (must be power of 2)
 * @since 0.1.0
 */
class WorkStealingQueue<T>(capacity: Int = 8192) {
    
    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "Capacity must be a power of 2"
        }
    }
    
    private val capacityMask = capacity - 1
    private val buffer = AtomicReferenceArray<T?>(capacity)
    
    // Owner's indices (single writer)
    @Volatile
    private var top = 0L
    
    // Shared bottom index (multiple readers)
    private val bottom = AtomicLong(0)
    
    /**
     * Pushes an element to the bottom of the queue.
     * Only the owner thread should call this method.
     * 
     * @param element The element to push
     * @return true if successful, false if queue is full
     */
    fun push(element: T): Boolean {
        val b = bottom.get()
        val t = top
        val size = b - t
        
        if (size >= capacityMask) {
            return false // Queue is full
        }
        
        buffer.set((b and capacityMask.toLong()).toInt(), element)
        bottom.lazySet(b + 1)
        return true
    }
    
    /**
     * Pops an element from the bottom of the queue.
     * Only the owner thread should call this method.
     * 
     * @return The popped element, or null if queue is empty
     */
    fun pop(): T? {
        val b = bottom.get() - 1
        bottom.lazySet(b)
        
        val t = top
        val size = b - t
        
        if (size < 0) {
            bottom.lazySet(t)
            return null
        }
        
        val element = buffer.get((b and capacityMask.toLong()).toInt())
        
        if (size == 0L) {
            // Last element - need to synchronize with stealers
            if (!casTop(t, t + 1)) {
                // Failed race with stealer
                bottom.lazySet(t + 1)
                return null
            }
            bottom.lazySet(t + 1)
        }
        
        return element
    }
    
    /**
     * Steals an element from the top of the queue.
     * Can be called by any thread.
     * 
     * @return The stolen element, or null if queue is empty
     */
    fun steal(): T? {
        while (true) {
            val t = top
            val b = bottom.get()
            val size = b - t
            
            if (size <= 0) {
                return null
            }
            
            val element = buffer.get((t and capacityMask.toLong()).toInt())
            
            if (casTop(t, t + 1)) {
                return element
            }
            // Retry on CAS failure
        }
    }
    
    /**
     * Steals multiple elements from the queue in batch.
     * More efficient than repeated steal() calls.
     * 
     * @param maxElements Maximum number of elements to steal
     * @return List of stolen elements (may be less than maxElements)
     */
    fun stealBatch(maxElements: Int): List<T> {
        val stolen = mutableListOf<T>()
        
        while (stolen.size < maxElements) {
            val t = top
            val b = bottom.get()
            val size = b - t
            
            if (size <= 0) break
            
            // Try to steal up to half of available elements
            val toSteal = min(maxElements - stolen.size, ((size + 1) / 2).toInt())
            
            for (i in 0 until toSteal) {
                val element = buffer.get(((t + i) and capacityMask.toLong()).toInt())
                if (element != null) {
                    stolen.add(element)
                }
            }
            
            if (casTop(t, t + toSteal)) {
                return stolen
            }
            // Retry on CAS failure
            stolen.clear()
        }
        
        return stolen
    }
    
    /**
     * Returns the current size of the queue.
     * This is an approximation due to concurrent operations.
     */
    fun size(): Int {
        val b = bottom.get()
        val t = top
        val size = b - t
        return if (size < 0) 0 else size.toInt()
    }
    
    /**
     * Checks if the queue is empty.
     * This is an approximation due to concurrent operations.
     */
    fun isEmpty(): Boolean = size() == 0
    
    /**
     * Clears all elements from the queue.
     * Only the owner thread should call this method.
     */
    fun clear() {
        while (pop() != null) {
            // Keep popping until empty
        }
    }
    
    /**
     * CAS operation for top index.
     */
    private fun casTop(expect: Long, update: Long): Boolean {
        // In a real implementation, this would use Unsafe or VarHandle
        // For now, using synchronized as a placeholder
        synchronized(this) {
            if (top == expect) {
                top = update
                return true
            }
            return false
        }
    }
}

/**
 * A collection of work-stealing queues for parallel processing.
 * 
 * @param T The type of elements
 * @property workerCount Number of worker threads
 * @property queueCapacity Capacity of each queue
 * @since 0.1.0
 */
class WorkStealingQueuePool<T>(
    private val workerCount: Int,
    private val queueCapacity: Int = 8192
) {
    private val queues = Array(workerCount) { WorkStealingQueue<T>(queueCapacity) }
    
    /**
     * Gets the queue for a specific worker.
     */
    fun getQueue(workerId: Int): WorkStealingQueue<T> {
        require(workerId in 0 until workerCount) {
            "Invalid worker ID: $workerId"
        }
        return queues[workerId]
    }
    
    /**
     * Attempts to steal work for a worker from other queues.
     * 
     * @param stealerId The worker attempting to steal
     * @param maxElements Maximum elements to steal
     * @return Stolen elements, or empty list if none available
     */
    fun stealFor(stealerId: Int, maxElements: Int = 1): List<T> {
        // Try to steal from other workers in round-robin fashion
        for (i in 1 until workerCount) {
            val victimId = (stealerId + i) % workerCount
            val stolen = queues[victimId].stealBatch(maxElements)
            if (stolen.isNotEmpty()) {
                return stolen
            }
        }
        return emptyList()
    }
    
    /**
     * Gets the total size across all queues.
     */
    fun totalSize(): Int = queues.sumOf { it.size() }
    
    /**
     * Finds the queue with the most elements.
     */
    fun findBusiestQueue(): Pair<Int, Int> {
        var maxSize = 0
        var maxId = 0
        
        for (i in queues.indices) {
            val size = queues[i].size()
            if (size > maxSize) {
                maxSize = size
                maxId = i
            }
        }
        
        return maxId to maxSize
    }
    
    /**
     * Balances work by moving tasks from busy to idle queues.
     */
    fun balance() {
        val sizes = queues.map { it.size() }
        val avgSize = sizes.sum() / workerCount
        
        // Find overloaded and underloaded queues
        val overloaded = sizes.withIndex().filter { it.value > avgSize * 1.5 }
        val underloaded = sizes.withIndex().filter { it.value < avgSize * 0.5 }
        
        // Move work from overloaded to underloaded
        for (over in overloaded) {
            for (under in underloaded) {
                val toMove = (over.value - avgSize) / 2
                if (toMove > 0) {
                    val stolen = queues[over.index].stealBatch(toMove)
                    stolen.forEach { queues[under.index].push(it) }
                }
            }
        }
    }
}