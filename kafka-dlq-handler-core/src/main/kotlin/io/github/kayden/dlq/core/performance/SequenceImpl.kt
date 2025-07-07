package io.github.kayden.dlq.core.performance

import java.util.concurrent.atomic.AtomicLong

/**
 * Default implementation of Sequence using AtomicLong.
 * 
 * Provides thread-safe sequence operations with memory ordering guarantees.
 * Uses padding to prevent false sharing in multi-threaded scenarios.
 * 
 * @param initialValue The initial sequence value (default: -1)
 * @since 0.1.0
 */
class SequenceImpl(initialValue: Long = INITIAL_VALUE) : Sequence {
    
    companion object {
        const val INITIAL_VALUE = -1L
        
        // Cache line padding to prevent false sharing
        private const val CACHE_LINE_PADDING = 7
    }
    
    // Padding before
    @Volatile private var p1: Long = 0L
    @Volatile private var p2: Long = 0L
    @Volatile private var p3: Long = 0L
    @Volatile private var p4: Long = 0L
    @Volatile private var p5: Long = 0L
    @Volatile private var p6: Long = 0L
    @Volatile private var p7: Long = 0L
    
    // The actual value
    private val value = AtomicLong(initialValue)
    
    // Padding after
    @Volatile private var p8: Long = 0L
    @Volatile private var p9: Long = 0L
    @Volatile private var p10: Long = 0L
    @Volatile private var p11: Long = 0L
    @Volatile private var p12: Long = 0L
    @Volatile private var p13: Long = 0L
    @Volatile private var p14: Long = 0L
    
    override fun get(): Long = value.get()
    
    override fun set(value: Long) {
        this.value.set(value)
    }
    
    override fun getAndSet(value: Long): Long = this.value.getAndSet(value)
    
    override fun incrementAndGet(): Long = value.incrementAndGet()
    
    override fun addAndGet(delta: Long): Long = value.addAndGet(delta)
    
    override fun compareAndSet(expectedValue: Long, newValue: Long): Boolean =
        value.compareAndSet(expectedValue, newValue)
    
    override fun setVolatile(value: Long) {
        this.value.lazySet(value)
    }
    
    // Prevent padding optimization
    fun sumPaddingToPreventOptimization(): Long {
        return p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9 + p10 + p11 + p12 + p13 + p14
    }
}

/**
 * Sequence barrier implementation for coordinating consumers.
 * 
 * @property sequencesToTrack The sequences to track for dependencies
 * @property waitStrategy The strategy to use when waiting
 * @property cursor The cursor sequence
 * @since 0.1.0
 */
class SequenceBarrierImpl(
    private val sequencesToTrack: Array<out Sequence>,
    private val waitStrategy: WaitStrategy,
    private val cursor: Sequence
) : SequenceBarrier {
    
    @Volatile
    private var alerted = false
    
    private val dependentSequence = if (sequencesToTrack.isEmpty()) {
        cursor
    } else {
        FixedSequenceGroup(sequencesToTrack)
    }
    
    override fun waitFor(sequence: Long): Long {
        checkAlert()
        
        val availableSequence = waitStrategy.waitFor(
            sequence,
            cursor,
            dependentSequence,
            this
        )
        
        return if (availableSequence < sequence) {
            availableSequence
        } else {
            getHighestPublishedSequence(sequence, availableSequence)
        }
    }
    
    override fun getCursor(): Long = cursor.get()
    
    override fun isAlerted(): Boolean = alerted
    
    override fun alert() {
        alerted = true
        waitStrategy.signalAllWhenBlocking()
    }
    
    override fun clearAlert() {
        alerted = false
    }
    
    override fun checkAlert() {
        if (alerted) {
            throw AlertException()
        }
    }
    
    private fun getHighestPublishedSequence(
        lowerBound: Long,
        availableSequence: Long
    ): Long {
        var highest = lowerBound
        for (sequence in lowerBound..availableSequence) {
            if (cursor.get() >= sequence) {
                highest = sequence
            } else {
                break
            }
        }
        return highest
    }
}

/**
 * Tracks the minimum sequence from a group of sequences.
 * 
 * Used for dependency tracking in multi-consumer scenarios.
 * 
 * @property sequences The sequences to track
 * @since 0.1.0
 */
internal class FixedSequenceGroup(
    private val sequences: Array<out Sequence>
) : Sequence {
    
    override fun get(): Long {
        var minimum = Long.MAX_VALUE
        for (sequence in sequences) {
            val value = sequence.get()
            if (value < minimum) {
                minimum = value
            }
        }
        return minimum
    }
    
    override fun set(value: Long) {
        throw UnsupportedOperationException("Cannot set value on a sequence group")
    }
    
    override fun getAndSet(value: Long): Long {
        throw UnsupportedOperationException("Cannot set value on a sequence group")
    }
    
    override fun incrementAndGet(): Long {
        throw UnsupportedOperationException("Cannot increment a sequence group")
    }
    
    override fun addAndGet(delta: Long): Long {
        throw UnsupportedOperationException("Cannot add to a sequence group")
    }
    
    override fun compareAndSet(expectedValue: Long, newValue: Long): Boolean {
        throw UnsupportedOperationException("Cannot compareAndSet on a sequence group")
    }
    
    override fun setVolatile(value: Long) {
        throw UnsupportedOperationException("Cannot set value on a sequence group")
    }
}