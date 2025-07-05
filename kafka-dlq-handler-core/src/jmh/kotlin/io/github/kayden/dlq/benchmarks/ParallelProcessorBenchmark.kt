package io.github.kayden.dlq.benchmarks

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.core.performance.*
import io.github.kayden.dlq.core.storage.InMemoryStorage
import kotlinx.coroutines.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 병렬 처리 성능 벤치마크.
 * 
 * 측정 항목:
 * - Work-stealing 효율성
 * - 파티션별 처리 성능
 * - CPU 친화성 영향
 * - 워커 수별 확장성
 * 
 * @since 0.1.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = ["-Xms4G", "-Xmx4G", "-XX:+UseG1GC"])
class ParallelProcessorBenchmark {
    
    @Param("4", "8", "16")
    var workerCount: Int = 8
    
    @Param("10", "50", "100")
    var partitionCount: Int = 50
    
    @Param("true", "false")
    var workStealing: Boolean = true
    
    @Param("true", "false")
    var cpuAffinity: Boolean = false
    
    private lateinit var parallelProcessor: ParallelProcessor
    private lateinit var storage: InMemoryStorage
    private lateinit var scope: CoroutineScope
    private val processedCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    
    @Setup(Level.Trial)
    fun setup() {
        storage = InMemoryStorage(maxSize = 1_000_000)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        
        val config = ParallelProcessorConfig(
            workerCount = workerCount,
            partitionCount = partitionCount,
            queueCapacity = 10000,
            enableWorkStealing = workStealing,
            stealThreshold = 100,
            enableCpuAffinity = cpuAffinity
        )
        
        parallelProcessor = WorkStealingParallelProcessor(
            storage = storage,
            recordHandler = { record ->
                // Simulate variable processing time based on partition
                val delay = when (record.originalPartition % 3) {
                    0 -> 5L   // Fast
                    1 -> 20L  // Medium
                    else -> 50L  // Slow
                }
                delay(delay)
                
                // Simulate occasional errors
                if (record.originalOffset % 100 == 0L) {
                    errorCount.incrementAndGet()
                    Result.failure(RuntimeException("Simulated error"))
                } else {
                    processedCount.incrementAndGet()
                    Result.success(Unit)
                }
            },
            config = config,
            scope = scope
        )
        
        runBlocking {
            parallelProcessor.start()
        }
    }
    
    @Benchmark
    fun processUniformLoad(blackhole: Blackhole) = runBlocking {
        val records = List(1000) { i ->
            createTestRecord(partition = i % partitionCount)
        }
        
        val results = records.map { record ->
            async { parallelProcessor.process(record) }
        }.awaitAll()
        
        blackhole.consume(results)
    }
    
    @Benchmark
    fun processSkewedLoad(blackhole: Blackhole) = runBlocking {
        // 80% of messages go to 20% of partitions
        val hotPartitions = (0 until partitionCount / 5).toList()
        
        val records = List(1000) { i ->
            val partition = if (i % 5 < 4) {
                hotPartitions.random()
            } else {
                (0 until partitionCount).random()
            }
            createTestRecord(partition = partition)
        }
        
        val results = records.map { record ->
            async { parallelProcessor.process(record) }
        }.awaitAll()
        
        blackhole.consume(results)
    }
    
    @Benchmark
    @OperationsPerInvocation(10000)
    fun processHighThroughput(blackhole: Blackhole) = runBlocking {
        // Simulate high-throughput scenario
        coroutineScope {
            repeat(10000) { i ->
                launch {
                    val record = createTestRecord(partition = i % partitionCount)
                    val result = parallelProcessor.process(record)
                    blackhole.consume(result)
                }
            }
        }
    }
    
    @Benchmark
    fun processBurstyLoad(blackhole: Blackhole) = runBlocking {
        // Process in bursts with pauses
        repeat(10) { burst ->
            // Burst of messages
            val burstRecords = List(500) { i ->
                createTestRecord(partition = (burst * 500 + i) % partitionCount)
            }
            
            val results = burstRecords.map { record ->
                async { parallelProcessor.process(record) }
            }.awaitAll()
            
            blackhole.consume(results)
            
            // Pause between bursts
            delay(50)
        }
    }
    
    @Benchmark
    fun processWithPartitionAffinity(blackhole: Blackhole) = runBlocking {
        // Messages from same partition in sequence (cache-friendly)
        val recordsByPartition = (0 until partitionCount).map { partition ->
            List(100) { createTestRecord(partition = partition) }
        }
        
        recordsByPartition.forEach { partitionRecords ->
            val results = partitionRecords.map { record ->
                async { parallelProcessor.process(record) }
            }.awaitAll()
            blackhole.consume(results)
        }
    }
    
    @Benchmark
    @Group("producerConsumer")
    @GroupThreads(3)
    fun producer() = runBlocking {
        repeat(100) {
            val record = createTestRecord(partition = it % partitionCount)
            parallelProcessor.process(record)
        }
    }
    
    @Benchmark
    @Group("producerConsumer")
    @GroupThreads(1)
    fun monitor(blackhole: Blackhole) = runBlocking {
        val metrics = parallelProcessor.getMetrics()
        blackhole.consume(metrics)
        delay(10)
    }
    
    private fun createTestRecord(partition: Int): DLQRecord {
        return DLQRecord(
            id = UUID.randomUUID().toString(),
            messageKey = "key-${System.nanoTime()}",
            originalTopic = "benchmark-topic",
            originalPartition = partition,
            originalOffset = System.nanoTime(),
            payload = ByteArray(1024) { it.toByte() },
            headers = mapOf(
                "partition" to partition.toString().toByteArray(),
                "timestamp" to System.currentTimeMillis().toString().toByteArray()
            ),
            errorClass = "BenchmarkException",
            errorMessage = "Benchmark test error",
            errorType = ErrorType.PROCESSING_ERROR,
            stackTrace = "Simulated stack trace",
            status = DLQStatus.PENDING,
            retryCount = 0,
            createdAt = System.currentTimeMillis()
        )
    }
    
    @TearDown(Level.Trial)
    fun tearDown() = runBlocking {
        parallelProcessor.stop()
        scope.cancel()
        
        val metrics = parallelProcessor.getMetrics()
        println("""
            |Parallel Processing Results:
            |  Total Processed: ${processedCount.get()}
            |  Total Errors: ${errorCount.get()}
            |  Throughput: ${metrics.throughput} msg/sec
            |  Average Latency: ${metrics.averageLatency} ms
            |  Queue Sizes: ${metrics.queueSizes}
            |  Steal Count: ${metrics.stealCount}
        """.trimMargin())
    }
}