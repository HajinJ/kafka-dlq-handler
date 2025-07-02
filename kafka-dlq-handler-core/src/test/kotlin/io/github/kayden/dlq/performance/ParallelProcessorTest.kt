package io.github.kayden.dlq.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ParallelProcessorTest {
    
    private lateinit var config: ParallelProcessorConfig
    private val processedCount = AtomicInteger(0)
    
    @BeforeEach
    fun setUp() {
        config = ParallelProcessorConfig(
            parallelism = 4,
            channelCapacity = 100,
            workStealingEnabled = true,
            partitionAware = true,
            workerIdleTimeout = 1.seconds
        )
        processedCount.set(0)
    }
    
    private fun createTestRecord(id: String = "test", partition: Int = 0): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key-$id",
            originalTopic = "test-topic",
            originalPartition = partition,
            originalOffset = 0L,
            payload = "test payload".toByteArray(),
            headers = emptyMap(),
            errorClass = "TestError",
            errorMessage = "Test error message",
            errorType = ErrorType.TRANSIENT,
            stackTrace = null,
            status = DLQStatus.PENDING
        )
    }
    
    @Test
    fun `should create processor with default config`() {
        val processor = ParallelProcessorFactory.create { record ->
            true
        }
        
        assertThat(processor).isNotNull
        val metrics = processor.getMetrics()
        assertThat(metrics.totalProcessed).isEqualTo(0)
        
        processor.shutdown()
    }
    
    @Test
    fun `should validate config parameters`() {
        assertThrows<IllegalArgumentException> {
            ParallelProcessorConfig(parallelism = -1)
        }
        
        assertThrows<IllegalArgumentException> {
            ParallelProcessorConfig(channelCapacity = 0)
        }
    }
    
    @Test
    fun `should process records in parallel`() = runTest {
        val processor = CoroutineParallelProcessor(
            config = config,
            processor = { record ->
                processedCount.incrementAndGet()
                delay(10) // Simulate processing
                true
            }
        )
        
        val records = (1..20).map { createTestRecord("record-$it") }
        
        val result = processor.process(records)
        
        // Allow time for processing
        delay(100)
        
        assertThat(processedCount.get()).isEqualTo(20)
        assertThat(result.successCount).isGreaterThan(0)
        assertThat(result.failureCount).isEqualTo(0)
        assertThat(result.throughput).isGreaterThan(0.0)
        
        processor.shutdown()
    }
    
    @Test
    fun `should handle failures gracefully`() = runTest {
        var errorHandled = false
        
        val processor = CoroutineParallelProcessor(
            config = config,
            processor = { record ->
                if (record.id.endsWith("5")) {
                    throw RuntimeException("Test error")
                }
                true
            }
        )
        
        val records = (1..10).map { createTestRecord("record-$it") }
        val result = processor.process(records)
        
        delay(100)
        
        // Error handling is done internally
        val metrics = processor.getMetrics()
        assertThat(metrics.failureCount).isGreaterThan(0)
        
        processor.shutdown()
    }
    
    @Test
    fun `should process with partition awareness`() = runTest {
        val partitionOrder = mutableMapOf<Int, MutableList<String>>()
        
        val processor = CoroutineParallelProcessor(
            config = config.copy(partitionAware = true),
            processor = { record ->
                synchronized(partitionOrder) {
                    partitionOrder
                        .computeIfAbsent(record.originalPartition) { mutableListOf() }
                        .add(record.id)
                }
                true
            }
        )
        
        val records = listOf(
            createTestRecord("1", 0),
            createTestRecord("2", 1),
            createTestRecord("3", 0),
            createTestRecord("4", 1),
            createTestRecord("5", 0)
        )
        
        processor.process(records)
        delay(100)
        
        // Records from same partition should maintain relative order
        assertThat(partitionOrder[0]).containsExactly("1", "3", "5")
        assertThat(partitionOrder[1]).containsExactly("2", "4")
        
        processor.shutdown()
    }
    
    @Test
    fun `should process stream of records`() = runTest {
        val channel = Channel<DLQRecord>(10)
        val processor = CoroutineParallelProcessor(
            config = config,
            processor = { record ->
                processedCount.incrementAndGet()
                true
            }
        )
        
        // Send records to channel
        launch {
            repeat(15) {
                channel.send(createTestRecord("record-$it"))
            }
            channel.close()
        }
        
        val result = processor.processStream(channel)
        delay(100)
        
        assertThat(processedCount.get()).isEqualTo(15)
        
        processor.shutdown()
    }
    
    @Test
    fun `should create CPU-bound processor`() = runTest {
        val processor = ParallelProcessorFactory.createCpuBound(
            processor = { record ->
                // Simulate CPU-intensive work
                Thread.sleep(1)
                true
            },
            parallelism = 2
        )
        
        val records = (1..10).map { createTestRecord("record-$it") }
        processor.process(records)
        
        delay(50)
        
        val metrics = processor.getMetrics()
        assertThat(metrics.totalProcessed).isGreaterThan(0)
        
        processor.shutdown()
    }
    
    @Test
    fun `should create IO-bound processor`() = runTest {
        val processor = ParallelProcessorFactory.createIoBound(
            processor = { record ->
                // Simulate IO operation
                delay(5)
                true
            }
        )
        
        val records = (1..10).map { createTestRecord("record-$it") }
        processor.process(records)
        
        delay(100)
        
        val metrics = processor.getMetrics()
        assertThat(metrics.totalProcessed).isGreaterThan(0)
        
        processor.shutdown()
    }
}

class IntegratedParallelProcessorTest {
    
    private val processedCount = AtomicInteger(0)
    
    @BeforeEach
    fun setUp() {
        processedCount.set(0)
    }
    
    private fun createTestRecord(id: String = "test"): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key-$id",
            originalTopic = "test-topic",
            originalPartition = 0,
            originalOffset = 0L,
            payload = "test payload".toByteArray(),
            headers = emptyMap(),
            errorClass = "TestError",
            errorMessage = "Test error message",
            errorType = ErrorType.TRANSIENT,
            stackTrace = null,
            status = DLQStatus.PENDING
        )
    }
    
    @Test
    fun `should process with integrated components`() = runTest {
        val processor = IntegratedParallelProcessor(
            config = ParallelProcessorConfig(parallelism = 2),
            processor = { record ->
                processedCount.incrementAndGet()
                delay(5)
                true
            }
        )
        
        val records = (1..30).map { createTestRecord("record-$it") }
        val result = processor.process(records)
        
        assertThat(processedCount.get()).isEqualTo(30)
        assertThat(result.successCount).isEqualTo(30)
        
        processor.shutdown()
    }
    
    @Test
    fun `should handle load properly`() = runTest {
        val processor = IntegratedParallelProcessor(
            config = ParallelProcessorConfig(parallelism = 1),
            processor = { record ->
                delay(10) // Slow processing
                true
            }
        )
        
        val records = (1..20).map { createTestRecord("record-$it") }
        processor.process(records)
        
        val metrics = processor.getMetrics()
        assertThat(metrics.totalProcessed).isGreaterThan(0)
        
        processor.shutdown()
    }
    
    @Test
    fun `should create processor with config`() = runTest {
        val processor = IntegratedParallelProcessor(
            config = ParallelProcessorConfig(
                parallelism = 4,
                channelCapacity = 128
            ),
            processor = { record ->
                processedCount.incrementAndGet()
                true
            }
        )
        
        val records = (1..50).map { createTestRecord("record-$it") }
        processor.process(records)
        
        delay(200)
        
        assertThat(processedCount.get()).isEqualTo(50)
        
        processor.shutdown()
    }
}

class BatchParallelProcessorTest {
    
    @BeforeEach
    fun setUp() {
        // Reset any shared state
    }
    
    private fun createTestRecord(id: String = "test"): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key-$id",
            originalTopic = "test-topic",
            originalPartition = 0,
            originalOffset = 0L,
            payload = "test payload".toByteArray(),
            headers = emptyMap(),
            errorClass = "TestError",
            errorMessage = "Test error message",
            errorType = ErrorType.TRANSIENT,
            stackTrace = null,
            status = DLQStatus.PENDING
        )
    }
    
    @Test
    fun `should process batches in parallel`() = runTest {
        val batchParallelProcessor = BatchParallelProcessor(
            parallelism = 4,
            batchSize = 10
        )
        
        val records = (1..100).map { createTestRecord("record-$it") }
        val recordFlow = kotlinx.coroutines.flow.flowOf(*records.toTypedArray())
        
        val results = mutableListOf<BatchResult>()
        batchParallelProcessor.processBatches(recordFlow).collect { result ->
            results.add(result)
        }
        
        assertThat(results).hasSize(10) // 100 records / 10 batch size
        assertThat(results.sumOf { it.successful }).isEqualTo(100)
        
        batchParallelProcessor.shutdown()
    }
}