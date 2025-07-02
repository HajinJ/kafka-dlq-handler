package io.github.kayden.dlq.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class RingBufferTest {
    
    private fun createTestRecord(id: String = "test-${Random.nextInt()}"): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key-$id",
            originalTopic = "test-topic",
            originalPartition = 0,
            originalOffset = 100L,
            payload = "test payload".toByteArray(),
            headers = mapOf("header1" to "value1".toByteArray()),
            errorClass = "java.lang.RuntimeException",
            errorMessage = "Test error",
            errorType = ErrorType.TRANSIENT,
            stackTrace = "Test stack trace",
            status = DLQStatus.PENDING
        )
    }
    
    @Test
    fun `should create ring buffer with valid power of 2 size`() {
        val validSizes = listOf(16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)
        
        validSizes.forEach { size ->
            val buffer = RingBuffer(size)
            assertEquals(size, buffer.capacity())
            assertTrue(buffer.isEmpty())
            assertEquals(0, buffer.size())
        }
    }
    
    @Test
    fun `should throw exception for non power of 2 size`() {
        val invalidSizes = listOf(0, -1, 3, 5, 7, 15, 17, 31, 33, 100, 1000)
        
        invalidSizes.forEach { size ->
            if (size <= 0) {
                assertThrows<IllegalArgumentException> {
                    RingBuffer(size)
                }
            } else {
                val exception = assertThrows<IllegalArgumentException> {
                    RingBuffer(size)
                }
                assertTrue(exception.message?.contains("power of 2") == true)
            }
        }
    }
    
    @Test
    fun `should offer and poll single record`() {
        val buffer = RingBuffer(16)
        val record = createTestRecord("test-1")
        
        assertTrue(buffer.offer(record))
        assertEquals(1, buffer.size())
        assertFalse(buffer.isEmpty())
        
        val polled = buffer.poll()
        assertEquals(record, polled)
        assertEquals(0, buffer.size())
        assertTrue(buffer.isEmpty())
    }
    
    @Test
    fun `should maintain FIFO order`() {
        val buffer = RingBuffer(64)
        val records = (1..10).map { createTestRecord("test-$it") }
        
        records.forEach { assertTrue(buffer.offer(it)) }
        
        records.forEach { expected ->
            val actual = buffer.poll()
            assertEquals(expected, actual)
        }
        
        assertNull(buffer.poll())
    }
    
    @Test
    fun `should handle buffer full scenario`() {
        val buffer = RingBuffer(16)
        val records = (1..16).map { createTestRecord("test-$it") }
        
        // Fill the buffer
        records.forEach { assertTrue(buffer.offer(it)) }
        assertEquals(16, buffer.size())
        assertTrue(buffer.isFull())
        
        // Try to add one more
        val extraRecord = createTestRecord("extra")
        assertFalse(buffer.offer(extraRecord))
        
        // Poll one and try again
        assertNotNull(buffer.poll())
        assertTrue(buffer.offer(extraRecord))
    }
    
    @Test
    fun `should handle wrap around correctly`() {
        val buffer = RingBuffer(8)
        
        // Fill buffer
        repeat(8) { assertTrue(buffer.offer(createTestRecord("first-$it"))) }
        
        // Remove half
        repeat(4) { assertNotNull(buffer.poll()) }
        
        // Add more (wrapping around)
        repeat(4) { assertTrue(buffer.offer(createTestRecord("second-$it"))) }
        
        // Verify we can still read all
        repeat(8) { assertNotNull(buffer.poll()) }
        assertNull(buffer.poll())
    }
    
    @Test
    fun `should return null when polling empty buffer`() {
        val buffer = RingBuffer(16)
        
        assertNull(buffer.poll())
        
        buffer.offer(createTestRecord())
        buffer.poll()
        
        assertNull(buffer.poll())
    }
    
    @Test
    fun `should calculate utilization correctly`() {
        val buffer = RingBuffer(16)
        
        assertEquals(0.0, buffer.utilization(), 0.01)
        
        repeat(4) { buffer.offer(createTestRecord()) }
        assertEquals(25.0, buffer.utilization(), 0.01)
        
        repeat(4) { buffer.offer(createTestRecord()) }
        assertEquals(50.0, buffer.utilization(), 0.01)
        
        repeat(8) { buffer.offer(createTestRecord()) }
        assertEquals(100.0, buffer.utilization(), 0.01)
    }
    
    @Test
    fun `should clear buffer correctly`() {
        val buffer = RingBuffer(16)
        
        repeat(10) { buffer.offer(createTestRecord()) }
        assertEquals(10, buffer.size())
        
        buffer.clear()
        assertEquals(0, buffer.size())
        assertTrue(buffer.isEmpty())
        assertNull(buffer.poll())
    }
    
    @Test
    fun `should handle concurrent offer operations`() = runBlocking {
        val buffer = RingBuffer(1024)
        val threads = 4
        val recordsPerThread = 100
        val successCount = AtomicInteger(0)
        
        val jobs = (1..threads).map { threadId ->
            launch(Dispatchers.Default) {
                repeat(recordsPerThread) { i ->
                    val record = createTestRecord("thread-$threadId-item-$i")
                    if (buffer.offer(record)) {
                        successCount.incrementAndGet()
                    }
                }
            }
        }
        
        jobs.joinAll()
        
        // Should have accepted most/all records
        assertTrue(successCount.get() >= threads * recordsPerThread * 0.9)
        assertTrue(buffer.size() <= 1024)
    }
    
    @Test
    fun `should handle concurrent poll operations`() = runBlocking {
        val buffer = RingBuffer(1024)
        val recordCount = 500
        val polledIds = ConcurrentLinkedQueue<String>()
        
        // Fill buffer with unique IDs
        val expectedIds = (0 until recordCount).map { "record-$it" }.toSet()
        expectedIds.forEach { id ->
            assertTrue(buffer.offer(createTestRecord(id)))
        }
        
        val jobs = (1..4).map {
            launch(Dispatchers.Default) {
                while (true) {
                    val record = buffer.poll() ?: break
                    polledIds.add(record.id)
                }
            }
        }
        
        jobs.joinAll()
        
        // Check that all records were polled exactly once
        val actualIds = polledIds.toSet()
        assertEquals(expectedIds, actualIds)
        assertEquals(recordCount, actualIds.size)
        assertTrue(buffer.isEmpty())
    }
    
    @Test
    fun `should handle mixed concurrent operations`() = runBlocking {
        val buffer = RingBuffer(256)
        val duration = 100L // milliseconds
        val produced = AtomicInteger(0)
        val consumed = AtomicInteger(0)
        
        val producers = (1..2).map { producerId ->
            launch(Dispatchers.Default) {
                val endTime = System.currentTimeMillis() + duration
                while (System.currentTimeMillis() < endTime) {
                    if (buffer.offer(createTestRecord("p$producerId-${produced.incrementAndGet()}"))) {
                        delay(1)
                    }
                }
            }
        }
        
        val consumers = (1..2).map {
            launch(Dispatchers.Default) {
                val endTime = System.currentTimeMillis() + duration
                while (System.currentTimeMillis() < endTime) {
                    if (buffer.poll() != null) {
                        consumed.incrementAndGet()
                    } else {
                        delay(1)
                    }
                }
            }
        }
        
        producers.joinAll()
        consumers.joinAll()
        
        // Drain remaining
        while (buffer.poll() != null) {
            consumed.incrementAndGet()
        }
        
        // All produced items should be consumed
        val tolerance = 10 // Allow small discrepancy due to timing
        assertTrue(Math.abs(produced.get() - consumed.get()) <= tolerance)
    }
    
    @Test
    fun `should maintain consistency under stress`() = runBlocking {
        val buffer = RingBuffer(512)
        val iterations = 1000
        val latch = CountDownLatch(1)
        
        val producer = launch(Dispatchers.Default) {
            latch.await()
            repeat(iterations) {
                while (!buffer.offer(createTestRecord("stress-$it"))) {
                    yield()
                }
            }
        }
        
        val consumer = launch(Dispatchers.Default) {
            latch.await()
            var consumed = 0
            while (consumed < iterations) {
                if (buffer.poll() != null) {
                    consumed++
                } else {
                    yield()
                }
            }
            assertEquals(iterations, consumed)
        }
        
        latch.countDown()
        producer.join()
        consumer.join()
        
        assertTrue(buffer.isEmpty())
    }
}