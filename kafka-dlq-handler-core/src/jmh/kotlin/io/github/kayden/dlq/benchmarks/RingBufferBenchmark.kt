package io.github.kayden.dlq.benchmarks

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.core.performance.RingBuffer
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * RingBuffer 성능 벤치마크.
 * 
 * 측정 항목:
 * - 단일 스레드 처리량 (ops/sec)
 * - 멀티 스레드 처리량
 * - 메모리 사용량
 * - GC 영향
 * 
 * @since 0.1.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = ["-Xms2G", "-Xmx2G"])
class RingBufferBenchmark {
    
    @Param("1024", "8192", "65536")
    var bufferSize: Int = 1024
    
    @Param("100", "1000", "10000")
    var messageSize: Int = 100
    
    private lateinit var ringBuffer: RingBuffer<DLQRecord>
    private lateinit var testRecord: DLQRecord
    
    @Setup(Level.Trial)
    fun setup() {
        ringBuffer = RingBuffer(bufferSize)
        testRecord = createTestRecord(messageSize)
    }
    
    @Benchmark
    fun offerSingleThread(blackhole: Blackhole) = runBlocking {
        val result = ringBuffer.offer(testRecord)
        blackhole.consume(result)
    }
    
    @Benchmark
    fun pollSingleThread(blackhole: Blackhole) = runBlocking {
        ringBuffer.offer(testRecord)
        val result = ringBuffer.poll()
        blackhole.consume(result)
    }
    
    @Benchmark
    @Threads(4)
    fun offerMultiThread(blackhole: Blackhole) = runBlocking {
        val result = ringBuffer.offer(testRecord)
        blackhole.consume(result)
    }
    
    @Benchmark
    @Threads(4)
    fun pollMultiThread(blackhole: Blackhole) = runBlocking {
        ringBuffer.offer(testRecord)
        val result = ringBuffer.poll()
        blackhole.consume(result)
    }
    
    @Benchmark
    fun offerBatch(blackhole: Blackhole) = runBlocking {
        val batch = List(100) { createTestRecord(messageSize) }
        val results = ringBuffer.offerBatch(batch)
        blackhole.consume(results)
    }
    
    @Benchmark
    fun pollBatch(blackhole: Blackhole) = runBlocking {
        val batch = List(100) { createTestRecord(messageSize) }
        ringBuffer.offerBatch(batch)
        val results = ringBuffer.pollBatch(100)
        blackhole.consume(results)
    }
    
    @Benchmark
    @Group("producerConsumer")
    @GroupThreads(2)
    fun producer() = runBlocking {
        ringBuffer.offer(testRecord)
    }
    
    @Benchmark
    @Group("producerConsumer")
    @GroupThreads(2)
    fun consumer(blackhole: Blackhole) = runBlocking {
        val result = ringBuffer.poll()
        blackhole.consume(result)
    }
    
    @Benchmark
    fun drainTo(blackhole: Blackhole) = runBlocking {
        // Fill buffer
        repeat(bufferSize / 2) {
            ringBuffer.offer(testRecord)
        }
        
        // Drain all
        val drained = mutableListOf<DLQRecord>()
        val count = ringBuffer.drainTo(drained)
        blackhole.consume(count)
        blackhole.consume(drained)
    }
    
    private fun createTestRecord(payloadSize: Int): DLQRecord {
        return DLQRecord(
            id = UUID.randomUUID().toString(),
            messageKey = "test-key",
            originalTopic = "test-topic",
            originalPartition = 0,
            originalOffset = 1000L,
            payload = ByteArray(payloadSize) { it.toByte() },
            headers = mapOf("test" to "header".toByteArray()),
            errorClass = "TestException",
            errorMessage = "Test error message",
            errorType = ErrorType.PROCESSING_ERROR,
            stackTrace = "Stack trace here",
            status = DLQStatus.PENDING,
            retryCount = 0,
            createdAt = System.currentTimeMillis()
        )
    }
    
    @TearDown(Level.Trial)
    fun tearDown() {
        // Cleanup if needed
    }
}