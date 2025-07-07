package io.github.kayden.dlq.core.performance

/**
 * Represents a sequence counter for tracking positions in the ring buffer.
 * 
 * This interface provides thread-safe operations for sequence management
 * in high-performance scenarios.
 * 
 * @since 0.1.0
 */
interface Sequence {
    
    /**
     * Gets the current value of the sequence.
     * 
     * @return The current sequence value
     */
    fun get(): Long
    
    /**
     * Sets the sequence to a specific value.
     * 
     * @param value The new sequence value
     */
    fun set(value: Long)
    
    /**
     * Atomically sets the value and returns the old value.
     * 
     * @param value The new sequence value
     * @return The previous value
     */
    fun getAndSet(value: Long): Long
    
    /**
     * Atomically increments the sequence and returns the new value.
     * 
     * @return The incremented value
     */
    fun incrementAndGet(): Long
    
    /**
     * Atomically adds the given value to the sequence.
     * 
     * @param delta The value to add
     * @return The updated value
     */
    fun addAndGet(delta: Long): Long
    
    /**
     * Performs a compare-and-set operation.
     * 
     * @param expectedValue The expected current value
     * @param newValue The new value to set
     * @return true if successful, false otherwise
     */
    fun compareAndSet(expectedValue: Long, newValue: Long): Boolean
    
    /**
     * Sets the value with volatile semantics.
     * 
     * @param value The new sequence value
     */
    fun setVolatile(value: Long)
}

/**
 * Barrier for coordinating sequence progression between producers and consumers.
 * 
 * @since 0.1.0
 */
interface SequenceBarrier {
    
    /**
     * Waits for the given sequence to be available.
     * 
     * @param sequence The sequence to wait for
     * @return The highest available sequence
     * @throws InterruptedException if interrupted while waiting
     */
    fun waitFor(sequence: Long): Long
    
    /**
     * Gets the current cursor value.
     * 
     * @return The current cursor sequence
     */
    fun getCursor(): Long
    
    /**
     * Checks if an alert has been raised.
     * 
     * @return true if alert is raised
     */
    fun isAlerted(): Boolean
    
    /**
     * Raises an alert to signal waiting threads.
     */
    fun alert()
    
    /**
     * Clears the current alert status.
     */
    fun clearAlert()
    
    /**
     * Checks and clears the alert status if it's raised.
     * 
     * @throws AlertException if alert was raised
     */
    fun checkAlert()
}

/**
 * Exception thrown when a sequence barrier is alerted.
 * 
 * @since 0.1.0
 */
class AlertException : Exception("Sequence barrier has been alerted")