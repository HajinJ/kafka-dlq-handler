package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RingBufferTest {
    
    @Test
    fun `should create ring buffer with power of 2 capacity`() {
        val buffer = RingBufferImpl(1024)
        assertEquals(1024, buffer.capacity)
    }
    
    @Test
    fun `should throw exception for non power of 2 capacity`() {
        assertThrows<IllegalArgumentException> {
            RingBufferImpl(1000)
        }
    }
    
    @Test
    fun `should throw exception for zero capacity`() {
        assertThrows<IllegalArgumentException> {
            RingBufferImpl(0)
        }
    }
    
    @Test
    fun `should throw exception for negative capacity`() {
        assertThrows<IllegalArgumentException> {
            RingBufferImpl(-16)
        }
    }
    
    @Test
    fun `should publish single record`() {
        val buffer = RingBufferImpl(16)
        val record = createTestRecord("test-1")
        
        val sequence = buffer.publish(record)
        
        assertEquals(0L, sequence)
        assertEquals(record, buffer.get(sequence))
    }
    
    @Test
    fun `should publish multiple records`() {
        val buffer = RingBufferImpl(16)
        val records = (1..5).map { createTestRecord("test-$it") }
        
        records.forEachIndexed { index, record ->
            val sequence = buffer.publish(record)
            assertEquals(index.toLong(), sequence)
            assertEquals(record, buffer.get(sequence))
        }
    }
    
    @Test
    fun `should publish batch of records`() {
        val buffer = RingBufferImpl(16)
        val records = (1..5).map { createTestRecord("test-$it") }
        
        val lastSequence = buffer.publishBatch(records)
        
        assertEquals(4L, lastSequence)
        records.forEachIndexed { index, record ->
            assertEquals(record, buffer.get(index.toLong()))
        }
    }
    
    @Test
    fun `should return -1 when publishing batch to full buffer`() {
        val buffer = RingBufferImpl(4)
        val records = (1..8).map { createTestRecord("test-$it") }
        
        // Fill the buffer first
        buffer.publishBatch(records.take(4))
        
        // Try to publish more than available capacity
        val result = buffer.publishBatch(records.drop(4))
        assertEquals(-1L, result)
    }
    
    @Test
    fun `should wrap around when buffer is full`() {
        val buffer = RingBufferImpl(4)
        val consumerSequence = SequenceImpl(-1L)
        buffer.addGatingSequence(consumerSequence)
        
        // Publish 4 records (fills the buffer)
        repeat(4) { i ->
            buffer.publish(createTestRecord("test-$i"))
        }
        
        // Consumer consumes first 2
        consumerSequence.set(1)
        
        // Now we should be able to publish 2 more
        val sequence1 = buffer.publish(createTestRecord("test-4"))
        val sequence2 = buffer.publish(createTestRecord("test-5"))
        
        assertEquals(4L, sequence1)
        assertEquals(5L, sequence2)
    }
    
    @Test
    fun `should check available capacity correctly`() {
        val buffer = RingBufferImpl(8)
        
        assertTrue(buffer.hasAvailableCapacity(8))
        
        // Publish 4 records
        repeat(4) {
            buffer.publish(createTestRecord("test-$it"))
        }
        
        assertTrue(buffer.hasAvailableCapacity(4))
        assertTrue(buffer.hasAvailableCapacity(3))
    }
    
    @Test
    fun `should calculate remaining capacity correctly`() {
        val buffer = RingBufferImpl(8)
        val consumerSequence = SequenceImpl(-1L)
        buffer.addGatingSequence(consumerSequence)
        
        assertEquals(8L, buffer.remainingCapacity())
        
        // Publish 4 records
        repeat(4) {
            buffer.publish(createTestRecord("test-$it"))
        }
        
        assertEquals(4L, buffer.remainingCapacity())
        
        // Consumer consumes 2
        consumerSequence.set(1)
        assertEquals(6L, buffer.remainingCapacity())
    }
    
    @Test
    fun `should get cursor position correctly`() {
        val buffer = RingBufferImpl(8)
        
        assertEquals(-1L, buffer.getCursor())
        
        buffer.publish(createTestRecord("test-1"))
        assertEquals(0L, buffer.getCursor())
        
        buffer.publish(createTestRecord("test-2"))
        assertEquals(1L, buffer.getCursor())
    }
    
    @Test
    fun `should create sequence barrier`() {
        val buffer = RingBufferImpl(8)
        val barrier = buffer.newBarrier()
        
        assertNotNull(barrier)
        assertEquals(-1L, barrier.getCursor())
    }
    
    @Test
    fun `should handle concurrent publishing`() = runBlocking {
        val buffer = RingBufferImpl(1024)
        val publishCount = AtomicInteger(0)
        val totalPublishes = 1000
        
        val jobs = (1..10).map { threadId ->
            async(Dispatchers.Default) {
                repeat(totalPublishes / 10) { i ->
                    val record = createTestRecord("thread-$threadId-msg-$i")
                    buffer.publish(record)
                    publishCount.incrementAndGet()
                }
            }
        }
        
        jobs.awaitAll()
        
        assertEquals(totalPublishes, publishCount.get())
        assertEquals((totalPublishes - 1).toLong(), buffer.getCursor())
    }
    
    @Test
    fun `should use different wait strategies`() {
        val strategies = listOf(
            BusySpinWaitStrategy(),
            YieldingWaitStrategy(),
            SleepingWaitStrategy(100_000L),
            BlockingWaitStrategy()
        )
        
        strategies.forEach { strategy ->
            val buffer = RingBufferImpl(16, strategy)
            val record = createTestRecord("test")
            val sequence = buffer.publish(record)
            assertEquals(0L, sequence)
            assertEquals(record, buffer.get(sequence))
        }
    }
    
    @Test
    fun `should build ring buffer with DSL`() {
        val buffer = ringBuffer {
            capacity(256)
            withYieldingWaitStrategy()
        }
        
        assertEquals(256, buffer.capacity)
        
        val record = createTestRecord("test")
        val sequence = buffer.publish(record)
        assertEquals(0L, sequence)
    }
    
    private fun createTestRecord(id: String): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key-$id",
            originalTopic = "test-topic",
            originalPartition = 0,
            originalOffset = 0L,
            payload = "payload-$id".toByteArray(),
            headers = mapOf("header1" to "value1".toByteArray()),
            errorClass = "TestException",
            errorMessage = "Test error",
            errorType = ErrorType.TRANSIENT_NETWORK_ERROR,
            stackTrace = null,
            status = DLQStatus.PENDING,
            retryCount = 0
        )
    }
}