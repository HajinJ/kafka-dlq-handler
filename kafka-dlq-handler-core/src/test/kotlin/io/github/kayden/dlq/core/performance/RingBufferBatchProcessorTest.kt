package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.core.storage.InMemoryStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RingBufferBatchProcessorTest {
    
    private val storage = InMemoryStorage()
    
    @Test
    fun `should create ring buffer batch processor`() = runTest {
        val processor = ringBufferBatchProcessor {
            storage(storage)
            recordHandler { Result.success(Unit) }
            ringBufferSize(1024)
            waitStrategy(YieldingWaitStrategy())
            configuration(BatchConfiguration.DEFAULT)
        }
        
        assertNotNull(processor)
    }
    
    @Test
    fun `should publish and process single record`() = runTest {
        val processedRecords = mutableListOf<String>()
        val processor = ringBufferBatchProcessor {
            storage(storage)
            recordHandler { record ->
                processedRecords.add(record.id)
                Result.success(Unit)
            }
            ringBufferSize(16)
            configuration(BatchConfiguration(
                minBatchSize = 1,
                maxBatchSize = 10,
                maxBatchDelay = Duration.ofMillis(50)
            ))
        }
        
        processor.start()
        
        val record = createTestRecord("test-1")
        val sequence = processor.publish(record)
        
        assertTrue(sequence >= 0)
        
        delay(100) // Wait for processing
        
        processor.stop()
        
        assertTrue(processedRecords.contains("test-1"))
    }
    
    @Test
    fun `should publish and process batch`() = runTest {
        val processedCount = AtomicInteger(0)
        val processor = ringBufferBatchProcessor {
            storage(storage)
            recordHandler { 
                processedCount.incrementAndGet()
                Result.success(Unit)
            }
            ringBufferSize(64)
            configuration(BatchConfiguration(
                minBatchSize = 5,
                maxBatchSize = 20,
                maxBatchDelay = Duration.ofMillis(50)
            ))
        }
        
        processor.start()
        
        val records = (1..15).map { createTestRecord("test-$it") }
        val lastSequence = processor.publishBatch(records)
        
        assertTrue(lastSequence >= 0)
        
        delay(200) // Wait for processing
        
        processor.stop()
        
        assertEquals(15, processedCount.get())
    }
    
    @Test
    fun `should handle buffer full condition`() = runTest {
        val processor = ringBufferBatchProcessor {
            storage(storage)
            recordHandler { 
                delay(1000) // Slow processing
                Result.success(Unit)
            }
            ringBufferSize(4) // Very small buffer
            configuration(BatchConfiguration(
                minBatchSize = 1,
                maxBatchSize = 2,
                parallelism = 1
            ))
        }
        
        processor.start()
        
        // Try to publish more than buffer capacity
        val sequences = mutableListOf<Long>()
        repeat(8) { i ->
            val seq = processor.publish(createTestRecord("test-$i"))
            sequences.add(seq)
        }
        
        // Some publishes should fail (return -1)
        assertTrue(sequences.any { it == -1L })
        
        processor.stop(Duration.ofMillis(100))
    }
    
    @Test
    fun `should process records with multiple consumers`() = runTest {
        val processedRecords = mutableSetOf<String>()
        val processor = ringBufferBatchProcessor {
            storage(storage)
            recordHandler { record ->
                synchronized(processedRecords) {
                    processedRecords.add(record.id)
                }
                Result.success(Unit)
            }
            ringBufferSize(256)
            configuration(BatchConfiguration(
                minBatchSize = 10,
                maxBatchSize = 50,
                maxBatchDelay = Duration.ofMillis(50),
                parallelism = 4
            ))
        }
        
        processor.start()
        
        // Publish many records
        val totalRecords = 200
        repeat(totalRecords) { i ->
            processor.publish(createTestRecord("test-$i"))
        }
        
        delay(500) // Wait for processing
        
        processor.stop()
        
        // All records should be processed
        assertEquals(totalRecords, processedRecords.size)
    }
    
    @Test
    fun `should integrate with flow processing`() = runTest {
        val processedCount = AtomicInteger(0)
        val processor = ringBufferBatchProcessor {
            storage(storage)
            recordHandler {
                processedCount.incrementAndGet()
                Result.success(Unit)
            }
            ringBufferSize(128)
            configuration(BatchConfiguration(
                minBatchSize = 10,
                maxBatchSize = 20
            ))
        }
        
        processor.start()
        
        val flow = kotlinx.coroutines.flow.flow {
            repeat(50) { i ->
                emit(createTestRecord("flow-$i"))
                delay(5) // Simulate streaming
            }
        }
        
        processor.processFlow(flow)
        
        delay(500) // Wait for processing
        
        processor.stop()
        
        assertTrue(processedCount.get() > 0)
    }
    
    @Test
    fun `should collect metrics through delegate`() = runTest {
        val processor = ringBufferBatchProcessor {
            storage(storage)
            recordHandler { Result.success(Unit) }
            ringBufferSize(128)
            configuration(BatchConfiguration.DEFAULT)
        }
        
        processor.start()
        
        // Publish some records
        repeat(100) { i ->
            processor.publish(createTestRecord("test-$i"))
        }
        
        delay(200) // Wait for processing
        
        val metrics = processor.getMetrics()
        
        assertTrue(metrics.totalProcessed > 0)
        assertTrue(metrics.averageThroughput > 0)
        
        processor.stop()
    }
    
    @Test
    fun `should handle different wait strategies`() = runTest {
        val strategies = listOf(
            BusySpinWaitStrategy(),
            YieldingWaitStrategy(),
            SleepingWaitStrategy(100_000L),
            BlockingWaitStrategy()
        )
        
        strategies.forEach { strategy ->
            val processedCount = AtomicInteger(0)
            val processor = ringBufferBatchProcessor {
                storage(storage)
                recordHandler {
                    processedCount.incrementAndGet()
                    Result.success(Unit)
                }
                ringBufferSize(16)
                waitStrategy(strategy)
                configuration(BatchConfiguration(
                    minBatchSize = 1,
                    maxBatchSize = 5
                ))
            }
            
            processor.start()
            
            repeat(10) { i ->
                processor.publish(createTestRecord("$strategy-$i"))
            }
            
            delay(100)
            processor.stop()
            
            assertTrue(processedCount.get() > 0, 
                "${strategy::class.simpleName} should process records")
        }
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