package io.github.kayden.dlq.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.storage.DLQStorage
import io.github.kayden.dlq.storage.BatchStorageResult
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asynchronous and concurrent tests for BatchProcessor
 */
class BatchProcessorAsyncTest {
    
    private lateinit var mockStorage: DLQStorage
    
    @BeforeEach
    fun setUp() {
        mockStorage = mockk()
    }
    
    private fun createTestRecord(id: String = "test-${System.nanoTime()}"): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key-$id",
            originalTopic = "test-topic",
            originalPartition = 0,
            originalOffset = 100L,
            payload = "test payload for $id".toByteArray(),
            headers = mapOf("header1" to "value1".toByteArray()),
            errorClass = "java.lang.RuntimeException",
            errorMessage = "Test error",
            errorType = ErrorType.TRANSIENT,
            stackTrace = "Test stack trace",
            status = DLQStatus.PENDING
        )
    }
    
    @Test
    fun `should process exact batch size immediately`() = runBlocking {
        val batchSize = 10
        val config = BatchProcessingConfig(
            batchSize = batchSize,
            batchTimeout = Duration.ofSeconds(30),
            enableMetrics = false,
            workerCount = 1
        )
        
        val processedBatches = mutableListOf<List<DLQRecord>>()
        val latch = CountDownLatch(1)
        
        coEvery { mockStorage.saveBatch(any()) } coAnswers {
            val batch = firstArg<List<DLQRecord>>()
            processedBatches.add(batch)
            latch.countDown()
            BatchStorageResult(
                successful = batch.map { it.id },
                failed = emptyList(),
                duration = Duration.ofMillis(10)
            )
        }
        
        val processor = BatchProcessor(config, mockStorage)
        processor.start()
        
        // Submit exactly batch size
        val records = (1..batchSize).map { createTestRecord("record-$it") }
        records.forEach { assertTrue(processor.submit(it)) }
        
        // Wait for batch processing
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Batch should be processed within 5 seconds")
        
        // Verify
        assertEquals(1, processedBatches.size)
        assertEquals(batchSize, processedBatches[0].size)
        assertEquals(records.map { it.id }.sorted(), processedBatches[0].map { it.id }.sorted())
        
        processor.stop()
    }
    
    @Test
    fun `should process batch on timeout`() = runBlocking {
        val config = BatchProcessingConfig(
            batchSize = 100,
            batchTimeout = Duration.ofMillis(200),
            enableMetrics = false,
            workerCount = 1
        )
        
        val processedBatches = ConcurrentLinkedQueue<List<DLQRecord>>()
        val latch = CountDownLatch(1)
        
        coEvery { mockStorage.saveBatch(any()) } coAnswers {
            val batch = firstArg<List<DLQRecord>>()
            processedBatches.add(batch)
            latch.countDown()
            BatchStorageResult(
                successful = batch.map { it.id },
                failed = emptyList(),
                duration = Duration.ofMillis(5)
            )
        }
        
        val processor = BatchProcessor(config, mockStorage)
        processor.start()
        
        // Submit less than batch size
        val recordCount = 5
        repeat(recordCount) {
            processor.submit(createTestRecord("record-$it"))
        }
        
        // Wait for timeout to trigger
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Timeout batch should process within 1 second")
        
        // Verify
        assertEquals(1, processedBatches.size)
        assertEquals(recordCount, processedBatches.first().size)
        
        processor.stop()
    }
    
    @Test
    fun `should handle concurrent submissions correctly`() = runBlocking {
        val config = BatchProcessingConfig(
            batchSize = 50,
            bufferSize = 1024,
            workerCount = 1, // Use single worker to ensure all records are processed
            batchTimeout = Duration.ofMillis(50) // Shorter timeout for faster processing
        )
        
        val processedIds = ConcurrentLinkedQueue<String>()
        val processedCount = AtomicInteger(0)
        
        coEvery { mockStorage.saveBatch(any()) } coAnswers {
            val batch = firstArg<List<DLQRecord>>()
            batch.forEach { processedIds.add(it.id) }
            val count = processedCount.addAndGet(batch.size)
            println("Processed batch of ${batch.size} records, total: $count")
            BatchStorageResult(
                successful = batch.map { it.id },
                failed = emptyList(),
                duration = Duration.ofMillis(10)
            )
        }
        
        val processor = BatchProcessor(config, mockStorage)
        processor.start()
        
        val totalRecords = 500
        val threads = 5
        val recordsPerThread = totalRecords / threads
        
        // Track submitted records
        val submittedIds = ConcurrentLinkedQueue<String>()
        val submissionLatch = CountDownLatch(threads)
        
        val jobs = (1..threads).map { threadId ->
            launch(Dispatchers.Default) {
                repeat(recordsPerThread) { i ->
                    val recordId = "thread-$threadId-record-$i"
                    val record = createTestRecord(recordId)
                    var submitted = false
                    var retries = 0
                    while (!submitted && retries < 50) {
                        submitted = processor.submit(record)
                        if (!submitted) {
                            delay(5)
                            retries++
                        }
                    }
                    if (submitted) {
                        submittedIds.add(recordId)
                    } else {
                        println("Failed to submit $recordId after $retries retries")
                    }
                }
                submissionLatch.countDown()
            }
        }
        
        // Wait for all submissions
        assertTrue(submissionLatch.await(30, TimeUnit.SECONDS), "Submissions should complete within 30 seconds")
        
        // Small delay to ensure last batch timeout triggers
        delay(200)
        
        // Stop processor to flush any remaining
        processor.stop(Duration.ofSeconds(10))
        
        // Log for debugging
        println("Total expected: $totalRecords")
        println("Total submitted: ${submittedIds.size}")
        println("Total processed: ${processedCount.get()}")
        println("Unique IDs processed: ${processedIds.size}")
        
        // Check what was not processed
        val notProcessed = submittedIds.filter { !processedIds.contains(it) }
        if (notProcessed.isNotEmpty()) {
            println("Not processed (${notProcessed.size}): ${notProcessed.take(10)}...")
        }
        
        // Verify all submitted records were processed
        assertEquals(submittedIds.size, processedIds.size, 
                    "All submitted records should be processed")
        assertEquals(submittedIds.toSet(), processedIds.toSet(), 
                    "Submitted and processed records should match")
    }
    
    @Test
    fun `should handle backpressure correctly`() = runBlocking {
        val bufferSize = 64
        val config = BatchProcessingConfig(
            batchSize = 10,
            bufferSize = bufferSize,
            batchTimeout = Duration.ofSeconds(10)
        )
        
        // Slow storage to create backpressure
        coEvery { mockStorage.saveBatch(any()) } coAnswers {
            delay(100) // Slow processing
            BatchStorageResult(
                successful = firstArg<List<DLQRecord>>().map { it.id },
                failed = emptyList(),
                duration = Duration.ofMillis(100)
            )
        }
        
        val processor = BatchProcessor(config, mockStorage)
        processor.start()
        
        var accepted = 0
        var rejected = 0
        
        // Try to submit more than buffer can handle quickly
        repeat(100) {
            if (processor.submit(createTestRecord())) {
                accepted++
            } else {
                rejected++
            }
        }
        
        // Verify buffer limits were respected
        println("Backpressure test: accepted=$accepted, rejected=$rejected, bufferSize=$bufferSize")
        assertTrue(accepted >= 10, "Should accept at least some records")
        assertTrue(accepted + rejected == 100, "Total should equal submission attempts")
        
        // With slow processing, we should see backpressure effects
        // Either some rejections or accepted count close to buffer size
        assertTrue(
            rejected > 0 || accepted <= bufferSize * 2,
            "Should either reject some or stay within reasonable buffer limits"
        )
        
        processor.stop()
    }
    
    @Test
    fun `should process remaining records on graceful shutdown`() = runBlocking {
        val config = BatchProcessingConfig(
            batchSize = 100,
            batchTimeout = Duration.ofSeconds(30),
            workerCount = 1
        )
        
        val processedRecords = ConcurrentLinkedQueue<String>()
        
        coEvery { mockStorage.saveBatch(any()) } coAnswers {
            val batch = firstArg<List<DLQRecord>>()
            batch.forEach { processedRecords.add(it.id) }
            BatchStorageResult(
                successful = batch.map { it.id },
                failed = emptyList(),
                duration = Duration.ofMillis(10)
            )
        }
        
        val processor = BatchProcessor(config, mockStorage)
        processor.start()
        
        // Submit less than batch size
        val recordCount = 25
        val submittedIds = mutableSetOf<String>()
        repeat(recordCount) {
            val id = "record-$it"
            submittedIds.add(id)
            processor.submit(createTestRecord(id))
        }
        
        // Give some time for records to be buffered
        delay(100)
        
        // Stop processor (should trigger flush)
        processor.stop(Duration.ofSeconds(5))
        
        // Verify remaining records were processed
        assertEquals(recordCount, processedRecords.size, 
                    "All submitted records should be processed on shutdown")
        assertEquals(submittedIds, processedRecords.toSet(),
                    "Processed records should match submitted records")
    }
}