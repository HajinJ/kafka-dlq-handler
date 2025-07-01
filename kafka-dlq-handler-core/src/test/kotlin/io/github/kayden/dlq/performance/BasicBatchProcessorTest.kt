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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Basic batch processor test to verify functionality
 */
class BasicBatchProcessorTest {
    
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
    fun `test basic batch processing`() = runBlocking {
        val batchSize = 5
        val config = BatchProcessingConfig(
            batchSize = batchSize,
            batchTimeout = Duration.ofMillis(100),
            enableMetrics = false,
            workerCount = 1
        )
        
        val processedCount = AtomicInteger(0)
        
        coEvery { mockStorage.saveBatch(any()) } coAnswers {
            val batch = firstArg<List<DLQRecord>>()
            println("Processing batch of size: ${batch.size}")
            processedCount.addAndGet(batch.size)
            BatchStorageResult(
                successful = batch.map { it.id },
                failed = emptyList(),
                duration = Duration.ofMillis(10)
            )
        }
        
        val processor = BatchProcessor(config, mockStorage)
        processor.start()
        
        // Submit exactly one batch
        repeat(batchSize) { i ->
            processor.submit(createTestRecord("record-$i"))
        }
        
        // Wait for processing
        delay(200)
        
        assertEquals(batchSize, processedCount.get())
        
        processor.stop()
    }
}