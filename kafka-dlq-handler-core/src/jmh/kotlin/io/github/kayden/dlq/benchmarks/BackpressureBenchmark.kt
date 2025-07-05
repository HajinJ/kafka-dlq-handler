package io.github.kayden.dlq.benchmarks

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.core.performance.*
import io.github.kayden.dlq.core.storage.InMemoryStorage
import kotlinx.coroutines.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 백프레셔 시스템 성능 벤치마크.
 * 
 * 측정 항목:
 * - 적응형 백프레셔 응답 시간
 * - Rate limiter 처리량
 * - Circuit breaker 오버헤드
 * - 과부하 상황에서의 성능 저하
 * 
 * @since 0.1.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = ["-Xms2G", "-Xmx2G", "-XX:+UseG1GC"])
class BackpressureBenchmark {
    
    @Param("ADAPTIVE", "THRESHOLD")
    lateinit var strategyType: String
    
    @Param("10000", "50000", "100000")
    var rateLimitPerSecond: Long = 50000
    
    @Param("true", "false")
    var circuitBreakerEnabled: Boolean = true
    
    private lateinit var backpressureProcessor: BackpressureAwareBatchProcessor
    private lateinit var storage: InMemoryStorage
    private lateinit var scope: CoroutineScope
    private val acceptedCount = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)
    private val processedCount = AtomicLong(0)
    
    @Setup(Level.Trial)
    fun setup() {
        storage = InMemoryStorage(maxSize = 1_000_000)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        
        val backpressureConfig = BackpressureConfig(
            maxPendingMessages = 100_000,
            maxMemoryUsage = 0.8,
            maxCpuUsage = 0.9,
            maxErrorRate = 0.1,
            checkInterval = Duration.ofMillis(100)
        )
        
        val strategy = when (strategyType) {
            "ADAPTIVE" -> AdaptiveBackpressureStrategy(backpressureConfig)
            "THRESHOLD" -> ThresholdBackpressureStrategy(backpressureConfig)
            else -> throw IllegalArgumentException("Unknown strategy: $strategyType")
        }
        
        val rateLimiter = TokenBucketRateLimiter(
            initialRate = rateLimitPerSecond.toDouble(),
            burstSize = (rateLimitPerSecond / 10).toInt()
        )
        
        val circuitBreaker = if (circuitBreakerEnabled) {
            DefaultCircuitBreaker(
                CircuitBreakerConfig(
                    failureThreshold = 10,
                    failureRateThreshold = 0.5,
                    openDuration = Duration.ofSeconds(30),
                    halfOpenMaxAttempts = 5,
                    slidingWindowSize = Duration.ofSeconds(10)
                )
            )
        } else null
        
        val batchConfig = BatchConfiguration(
            minBatchSize = 100,
            maxBatchSize = 1000,
            maxBatchDelay = Duration.ofMillis(50),
            adaptiveSizing = true
        )
        
        backpressureProcessor = BackpressureAwareBatchProcessor(
            storage = storage,
            recordHandler = { record ->
                // Simulate variable processing
                delay((5..15).random().toLong())
                processedCount.incrementAndGet()
                
                // Simulate occasional failures
                if (record.originalOffset % 50 == 0L) {
                    Result.failure(RuntimeException("Simulated failure"))
                } else {
                    Result.success(Unit)
                }
            },
            backpressureStrategy = strategy,
            rateLimiter = rateLimiter,
            circuitBreaker = circuitBreaker,
            config = batchConfig,
            scope = scope
        )
        
        runBlocking {
            backpressureProcessor.start()
        }
    }
    
    @Benchmark
    fun processUnderNormalLoad(blackhole: Blackhole) = runBlocking {
        // Normal load - within rate limits
        val records = List(100) { createTestRecord() }
        
        val results = records.map { record ->
            async {
                val result = backpressureProcessor.process(record)
                if (result.isSuccess) acceptedCount.incrementAndGet()
                else rejectedCount.incrementAndGet()
                result
            }
        }.awaitAll()
        
        blackhole.consume(results)
    }
    
    @Benchmark
    fun processUnderHighLoad(blackhole: Blackhole) = runBlocking {
        // High load - may trigger backpressure
        val records = List(1000) { createTestRecord() }
        
        val results = withTimeoutOrNull(5000) {
            records.map { record ->
                async {
                    val result = backpressureProcessor.process(record)
                    if (result.isSuccess) acceptedCount.incrementAndGet()
                    else rejectedCount.incrementAndGet()
                    result
                }
            }.awaitAll()
        } ?: emptyList()
        
        blackhole.consume(results)
    }
    
    @Benchmark
    @OperationsPerInvocation(10000)
    fun processSustainedLoad(blackhole: Blackhole) = runBlocking {
        // Sustained high load
        val startTime = System.currentTimeMillis()
        var count = 0
        
        while (count < 10000 && System.currentTimeMillis() - startTime < 10000) {
            launch {
                val record = createTestRecord()
                val result = backpressureProcessor.process(record)
                if (result.isSuccess) acceptedCount.incrementAndGet()
                else rejectedCount.incrementAndGet()
                blackhole.consume(result)
            }
            count++
            
            // Small delay to spread load
            if (count % 100 == 0) delay(1)
        }
    }
    
    @Benchmark
    fun processBurstLoad(blackhole: Blackhole) = runBlocking {
        // Burst pattern - alternating high and low load
        repeat(5) { iteration ->
            // Burst phase
            val burstSize = if (iteration % 2 == 0) 2000 else 200
            val burstRecords = List(burstSize) { createTestRecord() }
            
            val results = burstRecords.map { record ->
                async {
                    val result = backpressureProcessor.process(record)
                    if (result.isSuccess) acceptedCount.incrementAndGet()
                    else rejectedCount.incrementAndGet()
                    result
                }
            }.awaitAll()
            
            blackhole.consume(results)
            
            // Rest phase
            delay(100)
        }
    }
    
    @Benchmark
    fun processWithFailures(blackhole: Blackhole) = runBlocking {
        // Test circuit breaker behavior
        val records = List(500) { i ->
            createTestRecord(simulateError = i % 10 < 3) // 30% error rate
        }
        
        val results = records.map { record ->
            async {
                val result = backpressureProcessor.process(record)
                if (result.isSuccess) acceptedCount.incrementAndGet()
                else rejectedCount.incrementAndGet()
                result
            }
        }.awaitAll()
        
        blackhole.consume(results)
    }
    
    private fun createTestRecord(simulateError: Boolean = false): DLQRecord {
        return DLQRecord(
            id = UUID.randomUUID().toString(),
            messageKey = "bench-key-${System.nanoTime()}",
            originalTopic = "benchmark-topic",
            originalPartition = (0..9).random(),
            originalOffset = System.nanoTime(),
            payload = ByteArray(1024) { it.toByte() },
            headers = mapOf(
                "benchmark" to "true".toByteArray(),
                "error" to simulateError.toString().toByteArray()
            ),
            errorClass = if (simulateError) "SimulatedError" else "BenchmarkException",
            errorMessage = "Benchmark test error",
            errorType = if (simulateError) ErrorType.TRANSIENT_ERROR else ErrorType.PROCESSING_ERROR,
            stackTrace = "Simulated stack trace",
            status = DLQStatus.PENDING,
            retryCount = 0,
            createdAt = System.currentTimeMillis()
        )
    }
    
    @TearDown(Level.Trial)
    fun tearDown() = runBlocking {
        backpressureProcessor.stop()
        scope.cancel()
        
        val metrics = backpressureProcessor.getMetrics()
        println("""
            |Backpressure Results:
            |  Strategy: $strategyType
            |  Accepted: ${acceptedCount.get()}
            |  Rejected: ${rejectedCount.get()}
            |  Processed: ${processedCount.get()}
            |  Rate Limit: $rateLimitPerSecond/sec
            |  Circuit Breaker: $circuitBreakerEnabled
            |  Metrics: $metrics
        """.trimMargin())
    }
}