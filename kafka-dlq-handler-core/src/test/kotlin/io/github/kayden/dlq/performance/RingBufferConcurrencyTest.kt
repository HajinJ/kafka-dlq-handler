package io.github.kayden.dlq.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test RingBuffer under concurrent load
 */
class RingBufferConcurrencyTest {
    
    private fun createTestRecord(id: String): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key-$id",
            originalTopic = "test-topic",
            originalPartition = 0,
            originalOffset = 100L,
            payload = "test".toByteArray(),
            headers = emptyMap(),
            errorClass = "Test",
            errorMessage = "Test error",
            errorType = ErrorType.TRANSIENT,
            stackTrace = "stack",
            status = DLQStatus.PENDING
        )
    }
    
    @Test
    fun `ring buffer should not lose records under concurrent access`() = runBlocking {
        val buffer = RingBuffer(1024)
        val totalRecords = 500
        val threads = 5
        val recordsPerThread = totalRecords / threads
        
        val submittedIds = ConcurrentLinkedQueue<String>()
        val submissionLatch = CountDownLatch(threads)
        val rejectedCount = AtomicInteger(0)
        
        // Submit records concurrently
        val jobs = (1..threads).map { threadId ->
            launch(Dispatchers.Default) {
                repeat(recordsPerThread) { i ->
                    val recordId = "thread-$threadId-record-$i"
                    val record = createTestRecord(recordId)
                    
                    if (buffer.offer(record)) {
                        submittedIds.add(recordId)
                    } else {
                        rejectedCount.incrementAndGet()
                    }
                }
                submissionLatch.countDown()
            }
        }
        
        // Wait for submissions
        submissionLatch.await()
        
        // Poll all records
        val polledIds = mutableSetOf<String>()
        var consecutiveNulls = 0
        while (consecutiveNulls < 100) {
            val record = buffer.poll()
            if (record != null) {
                polledIds.add(record.id)
                consecutiveNulls = 0
            } else {
                consecutiveNulls++
                delay(1)
            }
        }
        
        println("Total records: $totalRecords")
        println("Submitted: ${submittedIds.size}")
        println("Rejected: ${rejectedCount.get()}")
        println("Polled: ${polledIds.size}")
        println("Buffer size after: ${buffer.size()}")
        
        // Verify no records lost
        assertEquals(0, rejectedCount.get(), "Should accept all records (buffer is large enough)")
        assertEquals(submittedIds.size, polledIds.size, "All submitted records should be polled")
        assertEquals(submittedIds.toSet(), polledIds, "Submitted and polled records should match")
    }
    
    @Test
    fun `ring buffer should handle producer-consumer pattern`() = runBlocking {
        val buffer = RingBuffer(128)
        val totalRecords = 1000
        
        val producedIds = ConcurrentLinkedQueue<String>()
        val consumedIds = ConcurrentLinkedQueue<String>()
        
        // Start consumer
        val consumerJob = launch {
            while (isActive) {
                val record = buffer.poll()
                if (record != null) {
                    consumedIds.add(record.id)
                } else {
                    delay(1)
                }
            }
        }
        
        // Start producer
        val producerJob = launch {
            repeat(totalRecords) { i ->
                val recordId = "record-$i"
                val record = createTestRecord(recordId)
                
                while (!buffer.offer(record)) {
                    delay(1) // Wait if buffer is full
                }
                producedIds.add(recordId)
                
                if (i % 100 == 0) {
                    delay(10) // Simulate work
                }
            }
        }
        
        // Wait for producer to finish
        producerJob.join()
        
        // Wait for consumer to catch up
        while (consumedIds.size < totalRecords) {
            delay(10)
        }
        
        consumerJob.cancel()
        
        println("Produced: ${producedIds.size}")
        println("Consumed: ${consumedIds.size}")
        
        assertEquals(totalRecords, producedIds.size)
        assertEquals(totalRecords, consumedIds.size)
        assertEquals(producedIds.toSet(), consumedIds.toSet())
    }
}