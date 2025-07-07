package io.github.kayden.dlq.core.performance

/**
 * Strategy for handling waiting when the ring buffer is empty or full.
 * 
 * Different strategies provide different trade-offs between CPU usage,
 * latency, and throughput.
 * 
 * @since 0.1.0
 */
interface WaitStrategy {
    
    /**
     * Waits for the given sequence to be available.
     * 
     * @param sequence The sequence to wait for
     * @param cursor The current cursor sequence
     * @param dependentSequence The sequence this wait depends on
     * @param barrier The sequence barrier for coordination
     * @return The available sequence (may be higher than requested)
     * @throws AlertException if the barrier is alerted while waiting
     * @throws InterruptedException if interrupted while waiting
     */
    fun waitFor(
        sequence: Long,
        cursor: Sequence,
        dependentSequence: Sequence,
        barrier: SequenceBarrier
    ): Long
    
    /**
     * Signals that a sequence has been published.
     * Used by some wait strategies to wake up waiting threads.
     */
    fun signalAllWhenBlocking()
}

/**
 * Wait strategy that spins in a busy loop.
 * 
 * Provides the lowest latency but highest CPU usage.
 * Best for low-latency scenarios where CPU usage is not a concern.
 * 
 * @since 0.1.0
 */
class BusySpinWaitStrategy : WaitStrategy {
    
    override fun waitFor(
        sequence: Long,
        cursor: Sequence,
        dependentSequence: Sequence,
        barrier: SequenceBarrier
    ): Long {
        var availableSequence: Long
        
        while (true) {
            availableSequence = dependentSequence.get()
            if (availableSequence >= sequence) {
                break
            }
            barrier.checkAlert()
        }
        
        return availableSequence
    }
    
    override fun signalAllWhenBlocking() {
        // No-op for busy spin
    }
}

/**
 * Wait strategy that yields the thread when waiting.
 * 
 * Provides a balance between latency and CPU usage.
 * Suitable for most use cases.
 * 
 * @since 0.1.0
 */
class YieldingWaitStrategy : WaitStrategy {
    
    private val spinTries = 100
    
    override fun waitFor(
        sequence: Long,
        cursor: Sequence,
        dependentSequence: Sequence,
        barrier: SequenceBarrier
    ): Long {
        var availableSequence: Long
        var counter = spinTries
        
        while (true) {
            availableSequence = dependentSequence.get()
            if (availableSequence >= sequence) {
                break
            }
            
            barrier.checkAlert()
            
            if (--counter == 0) {
                Thread.yield()
                counter = spinTries
            }
        }
        
        return availableSequence
    }
    
    override fun signalAllWhenBlocking() {
        // No-op for yielding
    }
}

/**
 * Wait strategy that sleeps when waiting.
 * 
 * Provides the lowest CPU usage but highest latency.
 * Suitable for background processing where latency is not critical.
 * 
 * @param sleepTimeNs The time to sleep in nanoseconds
 * @since 0.1.0
 */
class SleepingWaitStrategy(
    private val sleepTimeNs: Long = 200_000L // 200 microseconds default
) : WaitStrategy {
    
    override fun waitFor(
        sequence: Long,
        cursor: Sequence,
        dependentSequence: Sequence,
        barrier: SequenceBarrier
    ): Long {
        var availableSequence: Long
        
        while (true) {
            availableSequence = dependentSequence.get()
            if (availableSequence >= sequence) {
                break
            }
            
            barrier.checkAlert()
            Thread.sleep(0, sleepTimeNs.toInt())
        }
        
        return availableSequence
    }
    
    override fun signalAllWhenBlocking() {
        // No-op for sleeping
    }
}

/**
 * Wait strategy that blocks using locks.
 * 
 * Provides low CPU usage with moderate latency.
 * Uses condition variables for efficient blocking.
 * 
 * @since 0.1.0
 */
class BlockingWaitStrategy : WaitStrategy {
    
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val processorNotifyCondition = lock.newCondition()
    
    override fun waitFor(
        sequence: Long,
        cursor: Sequence,
        dependentSequence: Sequence,
        barrier: SequenceBarrier
    ): Long {
        var availableSequence = cursor.get()
        
        if (availableSequence < sequence) {
            lock.lock()
            try {
                availableSequence = cursor.get()
                while (availableSequence < sequence) {
                    barrier.checkAlert()
                    processorNotifyCondition.await()
                    availableSequence = cursor.get()
                }
            } finally {
                lock.unlock()
            }
        }
        
        availableSequence = dependentSequence.get()
        while (availableSequence < sequence) {
            barrier.checkAlert()
            availableSequence = dependentSequence.get()
        }
        
        return availableSequence
    }
    
    override fun signalAllWhenBlocking() {
        lock.lock()
        try {
            processorNotifyCondition.signalAll()
        } finally {
            lock.unlock()
        }
    }
}