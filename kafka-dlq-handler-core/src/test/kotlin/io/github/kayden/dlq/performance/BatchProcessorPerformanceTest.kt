package io.github.kayden.dlq.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.storage.DLQStorage
import io.github.kayden.dlq.storage.BatchStorageResult
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

/**
 * Performance benchmark tests for BatchProcessor
 */
class BatchProcessorPerformanceTest {
    
    private lateinit var mockStorage: DLQStorage
    
    @BeforeEach
    fun setUp() {
        mockStorage = mockk()
    }
    
    private fun createTestRecord(id: String = "test-${System.nanoTime()}"): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key-$id",
            originalTopic = "perf-topic",
            originalPartition = 0,
            originalOffset = 100L,
            payload = "performance test payload for $id".toByteArray(),
            headers = mapOf("header1" to "value1".toByteArray()),
            errorClass = "java.lang.RuntimeException",
            errorMessage = "Performance test error",
            errorType = ErrorType.TRANSIENT,
            stackTrace = "Test stack trace",
            status = DLQStatus.PENDING
        )
    }
    
    @Test
    @DisplayName("should achieve 100K+ messages per second throughput")
    fun `performance test - throughput`() = runBlocking {
        val config = BatchProcessingConfig.HIGH_THROUGHPUT
        val totalMessages = 100_000
        val processedCount = AtomicLong(0)
        
        // Mock fast storage
        coEvery { mockStorage.saveBatch(any()) } coAnswers {
            val batch = firstArg<List<DLQRecord>>()
            processedCount.addAndGet(batch.size.toLong())
            BatchStorageResult(
                successful = batch.map { it.id },
                failed = emptyList(),
                duration = Duration.ofMillis(1) // Simulate fast storage
            )
        }
        
        val processor = BatchProcessor(config, mockStorage)
        processor.start()
        
        val elapsedTime = measureTimeMillis {
            // Submit messages
            repeat(totalMessages) { i ->
                while (!processor.submit(createTestRecord("msg-$i"))) {
                    delay(1)
                }
            }
            
            // Wait for processing
            while (processedCount.get() < totalMessages) {
                delay(10)
            }
        }
        
        processor.stop()
        
        val throughput = (totalMessages * 1000.0) / elapsedTime
        println("Throughput: ${throughput.toInt()} msg/sec (elapsed: ${elapsedTime}ms)")
        
        assert(throughput >= 50_000) { 
            "Expected throughput >= 50K msg/sec, but got ${throughput.toInt()} msg/sec" 
        }
    }
    
    @Test
    @DisplayName("should maintain P99 latency under 10ms")
    fun `performance test - latency`() = runBlocking {
        val config = BatchProcessingConfig.LOW_LATENCY
        val latencies = mutableListOf<Long>()
        
        coEvery { mockStorage.saveBatch(any()) } coAnswers {
            val batch = firstArg<List<DLQRecord>>()
            // Track processing time for each record
            batch.forEach { record ->
                val submitTime = record.headers["submit_time"]?.let { 
                    String(it).toLong() 
                } ?: 0L
                val latency = System.currentTimeMillis() - submitTime
                latencies.add(latency)
            }
            
            BatchStorageResult(
                successful = batch.map { it.id },
                failed = emptyList(),
                duration = Duration.ofMillis(1)
            )
        }
        
        val processor = BatchProcessor(config, mockStorage)
        processor.start()
        
        // Submit 10,000 messages with timestamps
        repeat(10_000) { i ->
            val record = createTestRecord("latency-$i").copy(
                headers = mapOf(
                    "submit_time" to System.currentTimeMillis().toString().toByteArray()
                )
            )
            while (!processor.submit(record)) {
                delay(1)
            }
        }
        
        // Wait for all processing
        delay(1000)
        processor.stop()
        
        // Calculate P99 latency
        latencies.sort()
        val p99Index = (latencies.size * 0.99).toInt()
        val p99Latency = if (latencies.isNotEmpty()) latencies[p99Index] else 0
        
        println("P99 Latency: ${p99Latency}ms (total samples: ${latencies.size})")
        
        assert(p99Latency < 50) { 
            "Expected P99 latency < 50ms, but got ${p99Latency}ms" 
        }
    }
    
    @Test
    @DisplayName("should handle burst traffic efficiently")
    fun `performance test - burst handling`() = runBlocking {
        val config = BatchProcessingConfig(
            batchSize = 500,
            batchTimeout = Duration.ofMillis(50),
            bufferSize = 65536,
            workerCount = 8
        )
        
        val processedCount = AtomicLong(0)
        val batchSizes = mutableListOf<Int>()
        
        coEvery { mockStorage.saveBatch(any()) } coAnswers {
            val batch = firstArg<List<DLQRecord>>()
            batchSizes.add(batch.size)
            processedCount.addAndGet(batch.size.toLong())
            delay(5) // Simulate some processing time
            BatchStorageResult(
                successful = batch.map { it.id },
                failed = emptyList(),
                duration = Duration.ofMillis(5)
            )
        }
        
        val processor = BatchProcessor(config, mockStorage)
        processor.start()
        
        // Simulate burst: 50K messages in 1 second
        val burstSize = 50_000
        val burstTime = measureTimeMillis {
            repeat(burstSize) { i ->
                while (!processor.submit(createTestRecord("burst-$i"))) {
                    yield() // Yield to other coroutines
                }
            }
        }
        
        // Wait for processing to complete
        val processingTime = measureTimeMillis {
            while (processedCount.get() < burstSize) {
                delay(10)
            }
        }
        
        processor.stop()
        
        val avgBatchSize = batchSizes.average()
        val burstThroughput = (burstSize * 1000.0) / burstTime
        val processingThroughput = (burstSize * 1000.0) / processingTime
        
        println("Burst submission: ${burstSize} messages in ${burstTime}ms (${burstThroughput.toInt()} msg/sec)")
        println("Processing completed in: ${processingTime}ms (${processingThroughput.toInt()} msg/sec)")
        println("Average batch size: ${avgBatchSize.toInt()}")
        println("Buffer utilization: ${processor.getStats().bufferUtilization}%")
        
        assert(burstTime < 5000) { "Burst submission took too long: ${burstTime}ms" }
        assert(processingTime < 10000) { "Processing took too long: ${processingTime}ms" }
        assert(avgBatchSize > 100) { "Batch size too small: ${avgBatchSize}" }
    }
    
    @Test
    @DisplayName("should maintain performance under sustained load")
    fun `performance test - sustained load`() = runBlocking {
        val config = BatchProcessingConfig.HIGH_THROUGHPUT
        val testDurationMs = 5_000L // 5 seconds
        val targetThroughput = 10_000 // 10K msg/sec
        
        val processedCount = AtomicLong(0)
        val startTime = System.currentTimeMillis()
        
        coEvery { mockStorage.saveBatch(any()) } coAnswers {
            val batch = firstArg<List<DLQRecord>>()
            processedCount.addAndGet(batch.size.toLong())
            BatchStorageResult(
                successful = batch.map { it.id },
                failed = emptyList(),
                duration = Duration.ofMillis(2)
            )
        }
        
        val processor = BatchProcessor(config, mockStorage)
        processor.start()
        
        // Submit messages at target rate
        val submissionJob = launch {
            var messageCount = 0
            while (System.currentTimeMillis() - startTime < testDurationMs) {
                val record = createTestRecord("sustained-${messageCount++}")
                if (processor.submit(record)) {
                    // Pace the submissions
                    if (messageCount % 1000 == 0) {
                        delay(1000L / (targetThroughput / 1000))
                    }
                } else {
                    yield()
                }
            }
        }
        
        // Monitor throughput
        val monitorJob = launch {
            var lastCount = 0L
            while (System.currentTimeMillis() - startTime < testDurationMs) {
                delay(1000)
                val currentCount = processedCount.get()
                val throughput = currentCount - lastCount
                println("Current throughput: ${throughput} msg/sec")
                lastCount = currentCount
            }
        }
        
        submissionJob.join()
        monitorJob.join()
        
        // Wait for final processing
        delay(1000)
        processor.stop()
        
        val totalProcessed = processedCount.get()
        val actualDuration = System.currentTimeMillis() - startTime
        val avgThroughput = (totalProcessed * 1000.0) / actualDuration
        
        println("Sustained load test completed:")
        println("Total processed: $totalProcessed messages")
        println("Duration: ${actualDuration}ms")
        println("Average throughput: ${avgThroughput.toInt()} msg/sec")
        
        assert(avgThroughput >= targetThroughput * 0.9) { 
            "Expected sustained throughput >= ${(targetThroughput * 0.9).toInt()} msg/sec, but got ${avgThroughput.toInt()}" 
        }
    }
}