package io.github.kayden.dlq.core.performance

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SequenceTest {
    
    @Test
    fun `should initialize with default value`() {
        val sequence = SequenceImpl()
        assertEquals(-1L, sequence.get())
    }
    
    @Test
    fun `should initialize with custom value`() {
        val sequence = SequenceImpl(100L)
        assertEquals(100L, sequence.get())
    }
    
    @Test
    fun `should set and get value`() {
        val sequence = SequenceImpl()
        sequence.set(42L)
        assertEquals(42L, sequence.get())
    }
    
    @Test
    fun `should get and set atomically`() {
        val sequence = SequenceImpl(10L)
        val oldValue = sequence.getAndSet(20L)
        assertEquals(10L, oldValue)
        assertEquals(20L, sequence.get())
    }
    
    @Test
    fun `should increment and get`() {
        val sequence = SequenceImpl(0L)
        assertEquals(1L, sequence.incrementAndGet())
        assertEquals(2L, sequence.incrementAndGet())
        assertEquals(3L, sequence.incrementAndGet())
    }
    
    @Test
    fun `should add and get`() {
        val sequence = SequenceImpl(10L)
        assertEquals(15L, sequence.addAndGet(5L))
        assertEquals(25L, sequence.addAndGet(10L))
        assertEquals(20L, sequence.addAndGet(-5L))
    }
    
    @Test
    fun `should compare and set`() {
        val sequence = SequenceImpl(10L)
        
        // Successful CAS
        assertTrue(sequence.compareAndSet(10L, 20L))
        assertEquals(20L, sequence.get())
        
        // Failed CAS
        assertFalse(sequence.compareAndSet(10L, 30L))
        assertEquals(20L, sequence.get())
    }
    
    @Test
    fun `should handle concurrent increments`() = runBlocking {
        val sequence = SequenceImpl(0L)
        val incrementCount = 10000
        val threadCount = 10
        
        val jobs = (1..threadCount).map {
            async(Dispatchers.Default) {
                repeat(incrementCount / threadCount) {
                    sequence.incrementAndGet()
                }
            }
        }
        
        jobs.awaitAll()
        
        assertEquals(incrementCount.toLong(), sequence.get())
    }
    
    @Test
    fun `should handle concurrent CAS operations`() = runBlocking {
        val sequence = SequenceImpl(0L)
        val successCount = AtomicInteger(0)
        
        val jobs = (1..100).map { i ->
            async(Dispatchers.Default) {
                // Each thread tries to set its own value
                var current: Long
                do {
                    current = sequence.get()
                } while (!sequence.compareAndSet(current, i.toLong()))
                successCount.incrementAndGet()
            }
        }
        
        jobs.awaitAll()
        
        // All CAS operations should eventually succeed
        assertEquals(100, successCount.get())
    }
    
    @Test
    fun `FixedSequenceGroup should track minimum sequence`() {
        val seq1 = SequenceImpl(10L)
        val seq2 = SequenceImpl(20L)
        val seq3 = SequenceImpl(15L)
        
        val group = FixedSequenceGroup(arrayOf(seq1, seq2, seq3))
        
        assertEquals(10L, group.get())
        
        seq1.set(25L)
        assertEquals(15L, group.get())
        
        seq3.set(30L)
        assertEquals(20L, group.get())
    }
    
    @Test
    fun `FixedSequenceGroup should handle empty array`() {
        val group = FixedSequenceGroup(emptyArray())
        assertEquals(Long.MAX_VALUE, group.get())
    }
    
    @Test
    fun `FixedSequenceGroup should throw on mutation attempts`() {
        val group = FixedSequenceGroup(arrayOf(SequenceImpl(0L)))
        
        assertThrows<UnsupportedOperationException> { group.set(10L) }
        assertThrows<UnsupportedOperationException> { group.getAndSet(10L) }
        assertThrows<UnsupportedOperationException> { group.incrementAndGet() }
        assertThrows<UnsupportedOperationException> { group.addAndGet(5L) }
        assertThrows<UnsupportedOperationException> { group.compareAndSet(0L, 10L) }
        assertThrows<UnsupportedOperationException> { group.setVolatile(10L) }
    }
}

inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
    try {
        block()
        throw AssertionError("Expected ${T::class.simpleName} to be thrown")
    } catch (e: Throwable) {
        if (e !is T) {
            throw AssertionError("Expected ${T::class.simpleName} but got ${e::class.simpleName}", e)
        }
    }
}