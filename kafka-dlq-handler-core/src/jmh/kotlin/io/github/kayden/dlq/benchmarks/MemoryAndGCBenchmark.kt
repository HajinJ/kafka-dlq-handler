package io.github.kayden.dlq.benchmarks

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.core.performance.*
import io.github.kayden.dlq.core.storage.InMemoryStorage
import kotlinx.coroutines.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 메모리 사용량 및 GC 압력 벤치마크.
 * 
 * 측정 항목:
 * - 객체 할당률
 * - GC 빈도 및 지속 시간
 * - 메모리 풋프린트
 * - 메모리 누수 가능성
 * 
 * @since 0.1.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = [
    "-Xms1G", "-Xmx1G",  // Fixed heap size for consistent measurement
    "-XX:+UseG1GC",
    "-XX:+UnlockDiagnosticVMOptions",
    "-XX:+PrintGCDetails",
    "-XX:+PrintGCDateStamps",
    "-XX:+PrintTenuringDistribution",
    "-XX:-UseBiasedLocking",
    "-XX:+AlwaysPreTouch"
])
class MemoryAndGCBenchmark {
    
    @Param("100", "1000", "10000")
    var messageCount: Int = 1000
    
    @Param("1024", "10240", "102400")
    var payloadSize: Int = 1024
    
    @Param("RING_BUFFER", "BATCH", "PARALLEL")
    lateinit var processorType: String
    
    private lateinit var storage: InMemoryStorage
    private lateinit var scope: CoroutineScope
    
    // Memory tracking
    private val memoryBean = ManagementFactory.getMemoryMXBean()
    private val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
    
    @Setup(Level.Trial)
    fun setup() {
        storage = InMemoryStorage(maxSize = 100_000)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }
    
    @Benchmark
    fun measureObjectAllocation(blackhole: Blackhole) {
        val beforeHeap = memoryBean.heapMemoryUsage.used
        val beforeGcCount = gcBeans.sumOf { it.collectionCount }
        
        // Create and process records
        val records = List(messageCount) { 
            createTestRecord(payloadSize)
        }
        
        blackhole.consume(records)
        
        val afterHeap = memoryBean.heapMemoryUsage.used
        val afterGcCount = gcBeans.sumOf { it.collectionCount }
        
        val heapGrowth = afterHeap - beforeHeap
        val gcOccurred = afterGcCount - beforeGcCount
        
        blackhole.consume(heapGrowth)
        blackhole.consume(gcOccurred)
    }
    
    @Benchmark
    fun measureRingBufferMemory(blackhole: Blackhole) = runBlocking {
        val ringBuffer = RingBuffer<DLQRecord>(8192)
        
        // Fill and drain multiple times
        repeat(10) {
            // Fill buffer
            val records = List(1000) { createTestRecord(payloadSize) }
            records.forEach { ringBuffer.offer(it) }
            
            // Drain buffer
            val drained = mutableListOf<DLQRecord>()
            ringBuffer.drainTo(drained)
            
            blackhole.consume(drained)
        }
        
        // Force reference clearing
        System.gc()
        delay(100)
        
        val finalHeap = memoryBean.heapMemoryUsage.used
        blackhole.consume(finalHeap)
    }
    
    @Benchmark
    fun measureBatchProcessorMemory(blackhole: Blackhole) = runBlocking {
        val config = BatchConfiguration(
            minBatchSize = 100,
            maxBatchSize = 1000,
            maxBatchDelay = Duration.ofMillis(50),
            adaptiveSizing = true
        )
        
        val processor = DefaultBatchProcessor(
            storage = storage,
            recordHandler = { Result.success(Unit) },
            config = config,
            scope = scope
        )
        
        processor.start()
        
        val beforeHeap = memoryBean.heapMemoryUsage.used
        
        // Process messages
        val records = List(messageCount) { createTestRecord(payloadSize) }
        val results = records.map { processor.process(it) }
        
        blackhole.consume(results)
        
        // Wait for processing
        delay(100)
        
        val afterHeap = memoryBean.heapMemoryUsage.used
        val memoryGrowth = afterHeap - beforeHeap
        
        processor.stop()
        
        blackhole.consume(memoryGrowth)
    }
    
    @Benchmark
    fun measureParallelProcessorMemory(blackhole: Blackhole) = runBlocking {
        val config = ParallelProcessorConfig(
            workerCount = 8,
            partitionCount = 32,
            queueCapacity = 1000,
            enableWorkStealing = true
        )
        
        val processor = WorkStealingParallelProcessor(
            storage = storage,
            recordHandler = { Result.success(Unit) },
            config = config,
            scope = scope
        )
        
        processor.start()
        
        val gcCountBefore = gcBeans.sumOf { it.collectionCount }
        val gcTimeBefore = gcBeans.sumOf { it.collectionTime }
        
        // Process messages
        val records = List(messageCount) { i ->
            createTestRecord(payloadSize, partition = i % 32)
        }
        
        val results = records.map { 
            async { processor.process(it) }
        }.awaitAll()
        
        blackhole.consume(results)
        
        // Measure GC impact
        val gcCountAfter = gcBeans.sumOf { it.collectionCount }
        val gcTimeAfter = gcBeans.sumOf { it.collectionTime }
        
        val gcCount = gcCountAfter - gcCountBefore
        val gcTime = gcTimeAfter - gcTimeBefore
        
        processor.stop()
        
        blackhole.consume(gcCount)
        blackhole.consume(gcTime)
    }
    
    @Benchmark
    @OperationsPerInvocation(100000)
    fun measureSustainedLoadMemory(blackhole: Blackhole) = runBlocking {
        // Simulate sustained load to check for memory leaks
        val processor = when (processorType) {
            "RING_BUFFER" -> createRingBufferProcessor()
            "BATCH" -> createBatchProcessor()
            "PARALLEL" -> createParallelProcessor()
            else -> throw IllegalArgumentException("Unknown processor type")
        }
        
        processor.start()
        
        // Initial memory snapshot
        System.gc()
        delay(100)
        val initialHeap = memoryBean.heapMemoryUsage.used
        
        // Process many messages
        repeat(100) { batch ->
            val records = List(1000) { 
                createTestRecord(payloadSize)
            }
            
            records.forEach { record ->
                launch {
                    processor.process(record)
                }
            }
            
            // Allow some processing
            delay(10)
            
            // Periodic memory check
            if (batch % 10 == 0) {
                val currentHeap = memoryBean.heapMemoryUsage.used
                val growth = currentHeap - initialHeap
                blackhole.consume(growth)
            }
        }
        
        processor.stop()
        
        // Final memory check
        System.gc()
        delay(100)
        val finalHeap = memoryBean.heapMemoryUsage.used
        val totalGrowth = finalHeap - initialHeap
        
        blackhole.consume(totalGrowth)
    }
    
    private fun createTestRecord(size: Int, partition: Int = 0): DLQRecord {
        return DLQRecord(
            id = UUID.randomUUID().toString(),
            messageKey = "key-${System.nanoTime()}",
            originalTopic = "benchmark-topic",
            originalPartition = partition,
            originalOffset = System.nanoTime(),
            payload = ByteArray(size) { (it % 256).toByte() },
            headers = mapOf(
                "size" to size.toString().toByteArray(),
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
    
    private fun createRingBufferProcessor(): DLQHandler {
        return RingBufferBatchProcessor(
            storage = storage,
            recordHandler = { Result.success(Unit) },
            ringBufferSize = 8192,
            batchSize = 100,
            scope = scope
        )
    }
    
    private fun createBatchProcessor(): DLQHandler {
        return DefaultBatchProcessor(
            storage = storage,
            recordHandler = { Result.success(Unit) },
            config = BatchConfiguration(
                minBatchSize = 100,
                maxBatchSize = 1000,
                maxBatchDelay = Duration.ofMillis(50)
            ),
            scope = scope
        )
    }
    
    private fun createParallelProcessor(): DLQHandler {
        return WorkStealingParallelProcessor(
            storage = storage,
            recordHandler = { Result.success(Unit) },
            config = ParallelProcessorConfig(
                workerCount = 8,
                partitionCount = 32,
                queueCapacity = 1000
            ),
            scope = scope
        )
    }
    
    @TearDown(Level.Trial)
    fun tearDown() {
        scope.cancel()
        
        // Final GC stats
        println("""
            |Memory & GC Statistics:
            |  Processor Type: $processorType
            |  Message Count: $messageCount
            |  Payload Size: $payloadSize bytes
            |  
            |  Heap Usage:
            |    Used: ${memoryBean.heapMemoryUsage.used / 1024 / 1024} MB
            |    Committed: ${memoryBean.heapMemoryUsage.committed / 1024 / 1024} MB
            |    Max: ${memoryBean.heapMemoryUsage.max / 1024 / 1024} MB
            |  
            |  GC Stats:
            |${gcBeans.joinToString("\n") { gc ->
                "    ${gc.name}: ${gc.collectionCount} collections, ${gc.collectionTime}ms total"
            }}
        """.trimMargin())
    }
}