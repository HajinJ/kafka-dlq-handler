package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.core.storage.InMemoryStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Performance tests for ParallelProcessor.
 * These tests are disabled by default to avoid slowing down regular builds.
 * Enable them for performance testing and optimization.
 */
@Disabled("Performance tests - run manually")
class ParallelProcessorPerformanceTest {
    
    private lateinit var storage: InMemoryStorage
    
    @BeforeEach
    fun setup() {
        storage = InMemoryStorage()
    }
    
    private fun createTestRecord(size: Int = 1024): DLQRecord {
        val id = UUID.randomUUID().toString()
        val payload = ByteArray(size) { (it % 256).toByte() }
        
        return DLQRecord(
            id = id,
            messageKey = "key-$id",
            originalTopic = "perf-test-topic",
            originalPartition = (id.hashCode() % 10),
            originalOffset = System.currentTimeMillis(),
            payload = payload,
            headers = mapOf(
                "header1" to "value1".toByteArray(),
                "header2" to "value2".toByteArray()
            ),
            errorClass = "PerformanceTest",
            errorMessage = "Test message",
            errorType = ErrorType.TRANSIENT_NETWORK_ERROR,
            stackTrace = null,
            status = DLQStatus.PENDING,
            retryCount = 0
        )
    }
    
    @Test
    fun `benchmark throughput with different worker counts`() = runBlocking {
        val recordCount = 100_000
        val records = List(recordCount) { createTestRecord() }
        
        println("\n=== Throughput Benchmark (${recordCount} records) ===")
        
        val workerCounts = listOf(1, 2, 4, 8, 16, 32)
        
        for (workers in workerCounts) {
            val processor = WorkStealingParallelProcessor(
                storage = InMemoryStorage(), // Fresh storage
                recordHandler = { record ->
                    // Minimal processing to test raw throughput
                    Result.success(Unit)
                },
                config = ParallelProcessingConfig(
                    workerCount = workers,
                    partitionCount = workers * 4,
                    queueCapacity = 10000
                )
            )
            
            processor.start()
            
            val time = measureTimeMillis {
                processor.processParallel(records)
            }
            
            val throughput = (recordCount * 1000.0) / time
            println("Workers: $workers - Time: ${time}ms - Throughput: ${"%.0f".format(throughput)} records/sec")
            
            processor.stop(Duration.ofSeconds(5))
        }
    }
    
    @Test
    fun `benchmark latency percentiles`() = runBlocking {
        val recordCount = 10_000
        val latencies = mutableListOf<Long>()
        
        val processor = WorkStealingParallelProcessor(
            storage = InMemoryStorage(),
            recordHandler = { record ->
                Thread.sleep(1) // Simulate 1ms processing
                Result.success(Unit)
            },
            config = ParallelProcessingConfig(
                workerCount = Runtime.getRuntime().availableProcessors(),
                workStealingEnabled = true
            )
        )
        
        processor.start()
        
        println("\n=== Latency Benchmark ===")
        
        // Process records one by one to measure individual latency
        repeat(recordCount) {
            val record = createTestRecord()
            val latency = measureTimeMillis {
                runBlocking {
                    processor.processParallel(listOf(record))
                }
            }
            latencies.add(latency)
        }
        
        processor.stop(Duration.ofSeconds(5))
        
        // Calculate percentiles
        latencies.sort()
        val p50 = latencies[latencies.size / 2]
        val p90 = latencies[(latencies.size * 0.9).toInt()]
        val p95 = latencies[(latencies.size * 0.95).toInt()]
        val p99 = latencies[(latencies.size * 0.99).toInt()]
        val max = latencies.maxOrNull() ?: 0
        
        println("Latency Percentiles (ms):")
        println("  P50: $p50")
        println("  P90: $p90")
        println("  P95: $p95")
        println("  P99: $p99")
        println("  Max: $max")
    }
    
    @Test
    fun `benchmark work stealing efficiency`() = runBlocking {
        val recordCount = 50_000
        
        println("\n=== Work Stealing Efficiency ===")
        
        // Test with unbalanced load
        val records = List(recordCount) { createTestRecord() }
        
        // Partitioner that creates imbalanced load
        val imbalancedPartitioner: (DLQRecord) -> Int = { record ->
            // 80% to partition 0, 20% distributed to others
            if (record.id.hashCode() % 5 == 0) {
                record.id.hashCode() % 4
            } else {
                0
            }
        }
        
        // Test without work stealing
        val processorNoStealing = WorkStealingParallelProcessor(
            storage = InMemoryStorage(),
            recordHandler = { Result.success(Unit) },
            config = ParallelProcessingConfig(
                workerCount = 4,
                workStealingEnabled = false
            )
        )
        
        processorNoStealing.start()
        
        val timeNoStealing = measureTimeMillis {
            processorNoStealing.processParallel(records, imbalancedPartitioner)
        }
        
        processorNoStealing.stop(Duration.ofSeconds(5))
        
        // Test with work stealing
        val processorWithStealing = WorkStealingParallelProcessor(
            storage = InMemoryStorage(),
            recordHandler = { Result.success(Unit) },
            config = ParallelProcessingConfig(
                workerCount = 4,
                workStealingEnabled = true,
                stealThreshold = 100
            )
        )
        
        processorWithStealing.start()
        
        val timeWithStealing = measureTimeMillis {
            processorWithStealing.processParallel(records, imbalancedPartitioner)
        }
        
        val metrics = processorWithStealing.getMetrics()
        processorWithStealing.stop(Duration.ofSeconds(5))
        
        val improvement = ((timeNoStealing - timeWithStealing) * 100.0) / timeNoStealing
        
        println("Without work stealing: ${timeNoStealing}ms")
        println("With work stealing: ${timeWithStealing}ms")
        println("Improvement: ${"%.1f".format(improvement)}%")
        println("Total stolen: ${metrics.totalStolen}")
        println("Stealing rate: ${"%.1f".format(metrics.stealingRate)}%")
    }
    
    @Test
    fun `benchmark memory usage`() = runBlocking {
        val runtime = Runtime.getRuntime()
        val recordCounts = listOf(10_000, 50_000, 100_000, 500_000)
        
        println("\n=== Memory Usage Benchmark ===")
        
        for (count in recordCounts) {
            System.gc()
            Thread.sleep(1000)
            
            val memBefore = runtime.totalMemory() - runtime.freeMemory()
            
            val processor = WorkStealingParallelProcessor(
                storage = InMemoryStorage(),
                recordHandler = { Result.success(Unit) },
                config = ParallelProcessingConfig(
                    workerCount = 8,
                    queueCapacity = count / 8
                )
            )
            
            processor.start()
            
            val records = List(count) { createTestRecord(512) } // 512 byte payload
            processor.processParallel(records)
            
            val memAfter = runtime.totalMemory() - runtime.freeMemory()
            val memUsed = (memAfter - memBefore) / (1024 * 1024)
            val memPerRecord = (memAfter - memBefore).toDouble() / count
            
            println("Records: $count - Memory used: ${memUsed}MB - Per record: ${"%.0f".format(memPerRecord)} bytes")
            
            processor.stop(Duration.ofSeconds(5))
        }
    }
    
    @Test
    fun `benchmark different payload sizes`() = runBlocking {
        val recordCount = 10_000
        val payloadSizes = listOf(100, 1024, 10240, 102400) // 100B, 1KB, 10KB, 100KB
        
        println("\n=== Payload Size Impact ===")
        
        for (size in payloadSizes) {
            val processor = WorkStealingParallelProcessor(
                storage = InMemoryStorage(),
                recordHandler = { Result.success(Unit) },
                config = ParallelProcessingConfig.HIGH_THROUGHPUT
            )
            
            processor.start()
            
            val records = List(recordCount) { createTestRecord(size) }
            
            val time = measureTimeMillis {
                processor.processParallel(records)
            }
            
            val throughput = (recordCount * 1000.0) / time
            val mbPerSec = (recordCount * size) / (time * 1024.0)
            
            println("Payload: ${size}B - Time: ${time}ms - Throughput: ${"%.0f".format(throughput)} rec/sec - ${"%.1f".format(mbPerSec)} MB/sec")
            
            processor.stop(Duration.ofSeconds(5))
        }
    }
    
    @Test
    fun `benchmark scaling efficiency`() = runBlocking {
        val recordCount = 100_000
        val records = List(recordCount) { createTestRecord() }
        
        println("\n=== Scaling Efficiency ===")
        
        var baselineThroughput = 0.0
        
        for (workers in listOf(1, 2, 4, 8, 16)) {
            val processor = WorkStealingParallelProcessor(
                storage = InMemoryStorage(),
                recordHandler = { Result.success(Unit) },
                config = ParallelProcessingConfig(
                    workerCount = workers,
                    partitionCount = workers * 4
                )
            )
            
            processor.start()
            
            val time = measureTimeMillis {
                processor.processParallel(records)
            }
            
            val throughput = (recordCount * 1000.0) / time
            
            if (workers == 1) {
                baselineThroughput = throughput
            }
            
            val speedup = throughput / baselineThroughput
            val efficiency = (speedup / workers) * 100
            
            println("Workers: $workers - Speedup: ${"%.2f".format(speedup)}x - Efficiency: ${"%.1f".format(efficiency)}%")
            
            processor.stop(Duration.ofSeconds(5))
        }
    }
}