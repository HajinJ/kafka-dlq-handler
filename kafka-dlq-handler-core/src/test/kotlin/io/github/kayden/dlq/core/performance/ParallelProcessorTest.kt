package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.core.storage.InMemoryStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ParallelProcessorTest {
    
    private lateinit var storage: InMemoryStorage
    private lateinit var processor: ParallelProcessor
    private val processedCount = AtomicInteger(0)
    private val failedRecords = mutableSetOf<String>()
    
    @BeforeEach
    fun setup() {
        storage = InMemoryStorage()
        processedCount.set(0)
        failedRecords.clear()
    }
    
    @AfterEach
    fun tearDown() = runTest {
        if (::processor.isInitialized) {
            processor.stop(Duration.ofSeconds(5))
        }
    }
    
    private fun createProcessor(
        config: ParallelProcessingConfig = ParallelProcessingConfig.DEFAULT,
        failureRate: Double = 0.0
    ): ParallelProcessor {
        return WorkStealingParallelProcessor(
            storage = storage,
            recordHandler = { record ->
                processedCount.incrementAndGet()
                delay(1) // Simulate processing
                
                if (Math.random() < failureRate || failedRecords.contains(record.id)) {
                    Result.failure(Exception("Simulated failure"))
                } else {
                    Result.success(Unit)
                }
            },
            config = config
        )
    }
    
    private fun createTestRecord(id: String = UUID.randomUUID().toString()): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key-$id",
            originalTopic = "test-topic",
            originalPartition = 0,
            originalOffset = 0L,
            payload = "test-payload-$id".toByteArray(),
            headers = mapOf("test-header" to "value".toByteArray()),
            errorClass = "TestError",
            errorMessage = "Test error message",
            errorType = ErrorType.TRANSIENT_NETWORK_ERROR,
            stackTrace = null,
            status = DLQStatus.PENDING,
            retryCount = 0,
            lastRetryAt = null,
            processingMetadata = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }
    
    @Test
    fun `should process records in parallel`() = runTest {
        processor = createProcessor(
            config = ParallelProcessingConfig(workerCount = 4)
        )
        processor.start()
        
        val records = List(100) { createTestRecord() }
        
        val result = processor.processParallel(records)
        
        assertEquals(100, result.totalProcessed)
        assertEquals(100, result.totalSuccessful)
        assertEquals(0, result.totalFailed)
        assertTrue(result.throughput > 0)
        
        // Verify all records were saved
        assertEquals(100, storage.count())
    }
    
    @Test
    fun `should handle failures gracefully`() = runTest {
        processor = createProcessor(
            config = ParallelProcessingConfig(workerCount = 2),
            failureRate = 0.2 // 20% failure rate
        )
        processor.start()
        
        val records = List(100) { createTestRecord() }
        
        val result = processor.processParallel(records)
        
        assertEquals(100, result.totalProcessed)
        assertTrue(result.totalSuccessful > 70) // At least 70% success
        assertTrue(result.totalFailed > 0)
        assertEquals(result.totalSuccessful + result.totalFailed, 100)
    }
    
    @Test
    fun `should partition records correctly`() = runTest {
        processor = createProcessor(
            config = ParallelProcessingConfig(
                workerCount = 4,
                partitionCount = 8
            )
        )
        processor.start()
        
        val records = List(100) { createTestRecord() }
        val partitioner: (DLQRecord) -> Int = { it.id.hashCode() % 4 }
        
        val result = processor.processParallel(records, partitioner)
        
        // Verify partitioning
        assertEquals(4, result.successfulByPartition.size)
        assertEquals(100, result.successfulByPartition.values.sum())
        
        // Each partition should have some records
        result.successfulByPartition.values.forEach { count ->
            assertTrue(count > 0)
        }
    }
    
    @Test
    fun `should process flow of records`() = runTest {
        processor = createProcessor(
            config = ParallelProcessingConfig(workerCount = 2)
        )
        processor.start()
        
        val recordFlow = flow {
            repeat(50) {
                emit(createTestRecord())
                delay(10)
            }
        }
        
        var completedPartitions = 0
        processor.processFlow(recordFlow) { partition ->
            completedPartitions++
        }
        
        // Wait for processing
        delay(1000)
        
        assertTrue(processedCount.get() >= 45) // Most should be processed
    }
    
    @Test
    fun `should submit individual records`() = runTest {
        processor = createProcessor()
        processor.start()
        
        val record = createTestRecord("test-123")
        val ticket = processor.submitRecord(record)
        
        assertEquals("test-123", ticket.recordId)
        assertTrue(ticket.workerId >= 0)
        assertTrue(ticket.partitionId >= 0)
        
        // Wait for processing
        delay(100)
        
        // Verify record was processed
        val saved = storage.findById("test-123")
        assertNotNull(saved)
    }
    
    @Test
    fun `should enable work stealing`() = runTest {
        processor = createProcessor(
            config = ParallelProcessingConfig(
                workerCount = 4,
                workStealingEnabled = true,
                stealThreshold = 5
            )
        )
        processor.start()
        
        // Create unbalanced load - all records to partition 0
        val records = List(100) { createTestRecord() }
        val partitioner: (DLQRecord) -> Int = { 0 } // All to same partition
        
        val result = processor.processParallel(records, partitioner)
        
        assertEquals(100, result.totalProcessed)
        assertEquals(100, result.totalSuccessful)
        
        // Check metrics for work stealing
        val metrics = processor.getMetrics()
        assertTrue(metrics.totalStolen > 0, "Work stealing should have occurred")
    }
    
    @Test
    fun `should update configuration dynamically`() = runTest {
        val initialConfig = ParallelProcessingConfig(workerCount = 2)
        processor = createProcessor(config = initialConfig)
        processor.start()
        
        assertEquals(2, processor.getConfiguration().workerCount)
        
        // Update configuration
        val newConfig = ParallelProcessingConfig(workerCount = 4)
        processor.updateConfiguration(newConfig)
        
        assertEquals(4, processor.getConfiguration().workerCount)
    }
    
    @Test
    fun `should provide accurate metrics`() = runTest {
        processor = createProcessor(
            config = ParallelProcessingConfig(
                workerCount = 4,
                metricsInterval = Duration.ofMillis(100)
            )
        )
        processor.start()
        
        val records = List(200) { createTestRecord() }
        processor.processParallel(records)
        
        delay(200) // Let metrics update
        
        val metrics = processor.getMetrics()
        
        assertEquals(200L, metrics.totalProcessed)
        assertEquals(200L, metrics.totalSuccessful)
        assertEquals(0L, metrics.totalFailed)
        assertTrue(metrics.averageThroughput > 0)
        assertTrue(metrics.peakThroughput >= metrics.averageThroughput)
        
        // Check worker utilization
        assertEquals(4, metrics.workerUtilization.size)
        metrics.workerUtilization.values.forEach { utilization ->
            assertTrue(utilization >= 0.0 && utilization <= 1.0)
        }
    }
    
    @Test
    fun `should report worker states`() = runTest {
        processor = createProcessor(
            config = ParallelProcessingConfig(workerCount = 3)
        )
        processor.start()
        
        val states = processor.getWorkerStates()
        
        assertEquals(3, states.size)
        states.forEach { state ->
            assertTrue(state.workerId in 0..2)
            assertTrue(state.state in WorkerState.State.values())
            assertTrue(state.queueSize >= 0)
            assertTrue(state.processedCount >= 0)
        }
    }
    
    @Test
    fun `should handle high throughput`() = runTest {
        processor = createProcessor(
            config = ParallelProcessingConfig.HIGH_THROUGHPUT
        )
        processor.start()
        
        val records = List(10000) { createTestRecord() }
        
        val startTime = System.currentTimeMillis()
        val result = processor.processParallel(records)
        val duration = System.currentTimeMillis() - startTime
        
        assertEquals(10000, result.totalProcessed)
        assertEquals(10000, result.totalSuccessful)
        
        val throughput = (10000.0 / duration) * 1000
        println("Achieved throughput: $throughput records/second")
        assertTrue(throughput > 1000, "Throughput should exceed 1000 records/second")
    }
    
    @Test
    fun `should handle graceful shutdown`() = runTest {
        processor = createProcessor()
        processor.start()
        
        // Submit records
        val tickets = List(10) { 
            async { processor.submitRecord(createTestRecord()) }
        }.awaitAll()
        
        // Stop with timeout
        processor.stop(Duration.ofSeconds(5))
        
        // Verify processor stopped
        val states = processor.getWorkerStates()
        states.forEach { state ->
            assertEquals(WorkerState.State.STOPPED, state.state)
        }
    }
    
    @Test
    fun `should handle empty input`() = runTest {
        processor = createProcessor()
        processor.start()
        
        val result = processor.processParallel(emptyList())
        
        assertEquals(0, result.totalProcessed)
        assertEquals(0, result.totalSuccessful)
        assertEquals(0, result.totalFailed)
        assertEquals(0.0, result.throughput)
    }
    
    @Test
    fun `should process with CPU affinity enabled`() = runTest {
        processor = createProcessor(
            config = ParallelProcessingConfig(
                workerCount = 2,
                cpuAffinity = true
            )
        )
        processor.start()
        
        val records = List(100) { createTestRecord() }
        val result = processor.processParallel(records)
        
        assertEquals(100, result.totalProcessed)
        
        // Check CPU affinity in worker states
        val states = processor.getWorkerStates()
        states.forEach { state ->
            assertNotNull(state.cpuAffinity)
            assertTrue(state.cpuAffinity!! >= 0)
        }
    }
}