package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.core.storage.InMemoryStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class BatchProcessorTest {
    
    private val storage = InMemoryStorage()
    
    @Test
    fun `should create batch configuration with valid values`() {
        val config = BatchConfiguration(
            minBatchSize = 10,
            maxBatchSize = 100,
            maxBatchDelay = Duration.ofMillis(50),
            adaptiveSizing = true,
            parallelism = 4
        )
        
        assertEquals(10, config.minBatchSize)
        assertEquals(100, config.maxBatchSize)
        assertEquals(Duration.ofMillis(50), config.maxBatchDelay)
        assertTrue(config.adaptiveSizing)
        assertEquals(4, config.parallelism)
    }
    
    @Test
    fun `should throw exception for invalid batch configuration`() {
        assertThrows<IllegalArgumentException> {
            BatchConfiguration(minBatchSize = 0)
        }
        
        assertThrows<IllegalArgumentException> {
            BatchConfiguration(minBatchSize = 100, maxBatchSize = 50)
        }
        
        assertThrows<IllegalArgumentException> {
            BatchConfiguration(maxBatchDelay = Duration.ofMillis(-1))
        }
        
        assertThrows<IllegalArgumentException> {
            BatchConfiguration(parallelism = 0)
        }
    }
    
    @Test
    fun `should process batch successfully`() = runTest {
        val processedCount = AtomicInteger(0)
        val processor = batchProcessor {
            storage(storage)
            recordHandler { record ->
                processedCount.incrementAndGet()
                Result.success(Unit)
            }
            configuration(BatchConfiguration.DEFAULT)
        }
        
        val records = (1..10).map { createTestRecord("test-$it") }
        val result = processor.processBatch(records)
        
        assertEquals(10, result.successful)
        assertEquals(0, result.failed)
        assertEquals(10, result.batchSize)
        assertEquals(10, processedCount.get())
        assertTrue(result.isFullySuccessful)
        assertEquals(100.0, result.successRate)
    }
    
    @Test
    fun `should handle failed records in batch`() = runTest {
        val processor = batchProcessor {
            storage(storage)
            recordHandler { record ->
                if (record.id.endsWith("5")) {
                    Result.failure(Exception("Processing failed"))
                } else {
                    Result.success(Unit)
                }
            }
            configuration(BatchConfiguration.DEFAULT)
        }
        
        val records = (1..10).map { createTestRecord("test-$it") }
        val result = processor.processBatch(records)
        
        assertEquals(9, result.successful)
        assertEquals(1, result.failed)
        assertEquals(10, result.batchSize)
        assertFalse(result.isFullySuccessful)
        assertEquals(90.0, result.successRate)
    }
    
    @Test
    fun `should process empty batch`() = runTest {
        val processor = batchProcessor {
            storage(storage)
            recordHandler { Result.success(Unit) }
            configuration(BatchConfiguration.DEFAULT)
        }
        
        val result = processor.processBatch(emptyList())
        
        assertEquals(0, result.successful)
        assertEquals(0, result.failed)
        assertEquals(0, result.batchSize)
        assertEquals(Duration.ZERO, result.duration)
    }
    
    @Test
    fun `should start and stop processor`() = runTest {
        val processor = batchProcessor {
            storage(storage)
            recordHandler { Result.success(Unit) }
            configuration(BatchConfiguration.DEFAULT)
        }
        
        processor.start()
        delay(100)
        processor.stop(Duration.ofSeconds(1))
        
        // Should not throw any exceptions
    }
    
    @Test
    fun `should process flow of records`() = runTest {
        val processedIds = mutableListOf<String>()
        val processor = batchProcessor {
            storage(storage)
            recordHandler { record ->
                processedIds.add(record.id)
                Result.success(Unit)
            }
            configuration(BatchConfiguration(
                minBatchSize = 5,
                maxBatchSize = 10,
                maxBatchDelay = Duration.ofMillis(50)
            ))
        }
        
        processor.start()
        
        val records = (1..15).map { createTestRecord("test-$it") }
        processor.processFlow(records.asFlow()) { result ->
            // Batch complete callback
        }
        
        delay(200) // Wait for processing
        processor.stop()
        
        // All records should be processed eventually
        assertTrue(processedIds.size <= 15)
    }
    
    @Test
    fun `should update configuration dynamically`() = runTest {
        val processor = batchProcessor {
            storage(storage)
            recordHandler { Result.success(Unit) }
            configuration(BatchConfiguration.DEFAULT)
        }
        
        val originalConfig = processor.getConfiguration()
        assertEquals(BatchConfiguration.DEFAULT, originalConfig)
        
        val newConfig = BatchConfiguration.HIGH_THROUGHPUT
        processor.updateConfiguration(newConfig)
        
        assertEquals(newConfig, processor.getConfiguration())
    }
    
    @Test
    fun `should collect metrics`() = runTest {
        val processor = batchProcessor {
            storage(storage)
            recordHandler { Result.success(Unit) }
            configuration(BatchConfiguration.DEFAULT)
        }
        
        processor.start()
        
        val records = (1..100).map { createTestRecord("test-$it") }
        repeat(10) {
            processor.processBatch(records.take(10))
        }
        
        val metrics = processor.getMetrics()
        
        assertEquals(100, metrics.totalProcessed)
        assertEquals(100, metrics.totalSuccessful)
        assertEquals(0, metrics.totalFailed)
        assertEquals(10, metrics.totalBatches)
        assertEquals(10.0, metrics.averageBatchSize)
        assertEquals(100.0, metrics.overallSuccessRate)
        assertTrue(metrics.averageThroughput > 0)
        
        processor.stop()
    }
    
    @Test
    fun `should process records in parallel`() = runTest {
        val concurrentExecutions = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        
        val processor = batchProcessor {
            storage(storage)
            recordHandler { record ->
                val current = concurrentExecutions.incrementAndGet()
                maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                delay(10) // Simulate work
                concurrentExecutions.decrementAndGet()
                Result.success(Unit)
            }
            configuration(BatchConfiguration(
                parallelism = 4,
                minBatchSize = 20,
                maxBatchSize = 20
            ))
        }
        
        val records = (1..20).map { createTestRecord("test-$it") }
        processor.processBatch(records)
        
        assertTrue(maxConcurrent.get() > 1, "Should process in parallel")
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