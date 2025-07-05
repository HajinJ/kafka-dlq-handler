package io.github.kayden.dlq.benchmarks

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.core.performance.BatchConfiguration
import io.github.kayden.dlq.core.performance.BatchProcessor
import io.github.kayden.dlq.core.performance.DefaultBatchProcessor
import io.github.kayden.dlq.core.storage.InMemoryStorage
import kotlinx.coroutines.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * BatchProcessor 성능 벤치마크.
 * 
 * 측정 항목:
 * - 배치 처리 처리량
 * - 배치 크기별 성능
 * - 적응형 배치 성능
 * - 처리 지연시간
 * 
 * @since 0.1.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = ["-Xms2G", "-Xmx2G", "-XX:+UseG1GC"])
class BatchProcessorBenchmark {
    
    @Param("100", "1000", "5000")
    var batchSize: Int = 1000
    
    @Param("10", "50", "100")
    var processingDelayMs: Long = 10
    
    @Param("true", "false")
    var adaptiveBatching: Boolean = true
    
    private lateinit var batchProcessor: BatchProcessor
    private lateinit var storage: InMemoryStorage
    private lateinit var scope: CoroutineScope
    private val processedCount = AtomicLong(0)
    
    @Setup(Level.Trial)
    fun setup() {
        storage = InMemoryStorage(maxSize = 1_000_000)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        
        val config = BatchConfiguration(
            minBatchSize = batchSize / 10,
            maxBatchSize = batchSize,
            maxBatchDelay = Duration.ofMillis(100),
            adaptiveSizing = adaptiveBatching
        )
        
        batchProcessor = DefaultBatchProcessor(
            storage = storage,
            recordHandler = { record ->
                // Simulate processing
                delay(processingDelayMs)
                processedCount.incrementAndGet()
                Result.success(Unit)
            },
            config = config,
            scope = scope
        )
        
        runBlocking {
            batchProcessor.start()
        }
    }
    
    @Benchmark
    fun processSingleMessage(blackhole: Blackhole) = runBlocking {
        val record = createTestRecord()
        val result = batchProcessor.process(record)
        blackhole.consume(result)
    }
    
    @Benchmark
    fun processBatch(blackhole: Blackhole) = runBlocking {
        val batch = List(batchSize) { createTestRecord() }
        
        // Submit all records
        val results = batch.map { record ->
            async { batchProcessor.process(record) }
        }.awaitAll()
        
        blackhole.consume(results)
    }
    
    @Benchmark
    @OperationsPerInvocation(1000)
    fun processContinuousStream(blackhole: Blackhole) = runBlocking {
        repeat(1000) {
            val record = createTestRecord()
            launch {
                val result = batchProcessor.process(record)
                blackhole.consume(result)
            }
        }
    }
    
    @Benchmark
    fun processWithBackpressure(blackhole: Blackhole) = runBlocking {
        // Simulate backpressure by flooding the processor
        val records = List(batchSize * 10) { createTestRecord() }
        
        val results = records.map { record ->
            async {
                batchProcessor.process(record)
            }
        }.awaitAll()
        
        blackhole.consume(results)
    }
    
    @Benchmark
    fun processVariableSizeBatches(blackhole: Blackhole) = runBlocking {
        // Test adaptive batching with variable load
        val sizes = listOf(10, 50, 100, 500, 1000)
        
        sizes.forEach { size ->
            val batch = List(size) { createTestRecord() }
            val results = batch.map { record ->
                async { batchProcessor.process(record) }
            }.awaitAll()
            blackhole.consume(results)
            
            // Small delay between batches
            delay(10)
        }
    }
    
    private fun createTestRecord(): DLQRecord {
        return DLQRecord(
            id = UUID.randomUUID().toString(),
            messageKey = "bench-key-${System.nanoTime()}",
            originalTopic = "benchmark-topic",
            originalPartition = (0..9).random(),
            originalOffset = System.nanoTime(),
            payload = ByteArray(1024) { it.toByte() },
            headers = mapOf(
                "benchmark" to "true".toByteArray(),
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
        batchProcessor.stop()
        scope.cancel()
        println("Total processed: ${processedCount.get()}")
    }
    
    @State(Scope.Thread)
    class ThreadState {
        val random = java.util.Random()
    }
}