package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.core.storage.InMemoryStorage
import kotlinx.coroutines.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis
import kotlin.test.assertTrue

/**
 * Performance tests for BatchProcessor implementations.
 * 
 * These tests are disabled by default to avoid running during regular builds.
 * Enable them when you need to measure performance.
 */
@Disabled("Performance tests - enable manually when needed")
class BatchProcessorPerformanceTest {
    
    private val storage = InMemoryStorage()
    
    @Test
    fun `measure DefaultBatchProcessor throughput`() = runBlocking {
        val totalRecords = 100_000
        val processedCount = AtomicLong(0)
        
        val processor = batchProcessor {
            storage(storage)
            recordHandler { 
                processedCount.incrementAndGet()
                Result.success(Unit)
            }
            configuration(BatchConfiguration(
                minBatchSize = 100,
                maxBatchSize = 1000,
                maxBatchDelay = Duration.ofMillis(100),
                adaptiveSizing = true,
                parallelism = 8
            ))
        }
        
        processor.start()
        
        val timeMillis = measureTimeMillis {
            val batches = totalRecords / 1000
            repeat(batches) {
                val records = (1..1000).map { createTestRecord("perf-$it") }
                processor.processBatch(records)
            }
        }
        
        processor.stop()
        
        val throughput = (totalRecords * 1000.0) / timeMillis
        println("DefaultBatchProcessor throughput: ${throughput.toInt()} records/sec")
        println("Total time: ${timeMillis}ms for $totalRecords records")
        
        assertTrue(throughput > 50_000, "Should achieve >50K records/sec")
    }
    
    @Test
    fun `measure RingBufferBatchProcessor throughput`() = runBlocking {
        val totalRecords = 100_000
        val processedCount = AtomicLong(0)
        
        val processor = ringBufferBatchProcessor {
            storage(storage)
            recordHandler {
                processedCount.incrementAndGet()
                Result.success(Unit)
            }
            ringBufferSize(65536)
            waitStrategy(YieldingWaitStrategy())
            configuration(BatchConfiguration(
                minBatchSize = 100,
                maxBatchSize = 1000,
                maxBatchDelay = Duration.ofMillis(50),
                adaptiveSizing = true,
                parallelism = 8
            ))
        }
        
        processor.start()
        
        val timeMillis = measureTimeMillis {
            // Publish all records
            repeat(totalRecords) { i ->
                var published = false
                while (!published) {
                    val seq = processor.publish(createTestRecord("perf-$i"))
                    if (seq >= 0) {
                        published = true
                    } else {
                        yield() // Buffer full, yield to consumers
                    }
                }
            }
            
            // Wait for all to be processed
            while (processedCount.get() < totalRecords) {
                delay(10)
            }
        }
        
        processor.stop()
        
        val throughput = (totalRecords * 1000.0) / timeMillis
        println("RingBufferBatchProcessor throughput: ${throughput.toInt()} records/sec")
        println("Total time: ${timeMillis}ms for $totalRecords records")
        
        assertTrue(throughput > 100_000, "Should achieve >100K records/sec")
    }
    
    @Test
    fun `compare batch sizes impact on throughput`() = runBlocking {
        val batchSizes = listOf(10, 50, 100, 500, 1000, 5000)
        val recordsPerTest = 50_000
        
        batchSizes.forEach { batchSize ->
            val processedCount = AtomicLong(0)
            
            val processor = batchProcessor {
                storage(storage)
                recordHandler {
                    processedCount.incrementAndGet()
                    Result.success(Unit)
                }
                configuration(BatchConfiguration(
                    minBatchSize = batchSize,
                    maxBatchSize = batchSize,
                    adaptiveSizing = false,
                    parallelism = 8
                ))
            }
            
            processor.start()
            
            val timeMillis = measureTimeMillis {
                val batches = recordsPerTest / batchSize
                repeat(batches) {
                    val records = (1..batchSize).map { createTestRecord("batch-$it") }
                    processor.processBatch(records)
                }
            }
            
            processor.stop()
            
            val throughput = (recordsPerTest * 1000.0) / timeMillis
            println("Batch size $batchSize: ${throughput.toInt()} records/sec")
        }
    }
    
    @Test
    fun `measure adaptive sizing effectiveness`() = runBlocking {
        val processor = batchProcessor {
            storage(storage)
            recordHandler { Result.success(Unit) }
            configuration(BatchConfiguration(
                minBatchSize = 10,
                maxBatchSize = 5000,
                adaptiveSizing = true,
                parallelism = 8
            ))
        }
        
        processor.start()
        
        // Simulate varying load
        repeat(10) { phase ->
            val load = when (phase % 3) {
                0 -> 1000   // Low load
                1 -> 10000  // High load
                else -> 5000 // Medium load
            }
            
            val timeMillis = measureTimeMillis {
                val records = (1..load).map { createTestRecord("adaptive-$it") }
                processor.processBatch(records)
            }
            
            val throughput = (load * 1000.0) / timeMillis
            println("Phase $phase (load=$load): ${throughput.toInt()} records/sec")
            
            delay(100) // Let adaptive sizing adjust
        }
        
        val metrics = processor.getMetrics()
        println("Final average batch size: ${metrics.averageBatchSize}")
        println("Peak throughput: ${metrics.peakThroughput}")
        
        processor.stop()
    }
    
    @Test
    fun `measure latency distribution`() = runBlocking {
        val latencies = mutableListOf<Long>()
        
        val processor = batchProcessor {
            storage(storage)
            recordHandler { Result.success(Unit) }
            configuration(BatchConfiguration(
                minBatchSize = 100,
                maxBatchSize = 100,
                adaptiveSizing = false
            ))
        }
        
        processor.start()
        
        repeat(1000) {
            val records = (1..100).map { createTestRecord("latency-$it") }
            
            val latency = measureTimeMillis {
                processor.processBatch(records)
            }
            
            latencies.add(latency)
        }
        
        processor.stop()
        
        latencies.sort()
        val p50 = latencies[latencies.size / 2]
        val p90 = latencies[(latencies.size * 0.9).toInt()]
        val p99 = latencies[(latencies.size * 0.99).toInt()]
        
        println("Latency distribution (ms):")
        println("  P50: $p50")
        println("  P90: $p90")
        println("  P99: $p99")
        
        assertTrue(p99 < 100, "P99 latency should be < 100ms")
    }
    
    private fun createTestRecord(id: String): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key",
            originalTopic = "perf-topic",
            originalPartition = 0,
            originalOffset = 0L,
            payload = ByteArray(100), // 100 byte payload
            headers = emptyMap(),
            errorClass = "Error",
            errorMessage = "Test",
            errorType = ErrorType.TRANSIENT_NETWORK_ERROR,
            stackTrace = null,
            status = DLQStatus.PENDING,
            retryCount = 0
        )
    }
}