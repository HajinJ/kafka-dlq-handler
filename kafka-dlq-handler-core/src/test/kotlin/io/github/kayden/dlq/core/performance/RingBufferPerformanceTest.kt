package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import kotlinx.coroutines.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis
import kotlin.test.assertTrue

/**
 * Performance tests for RingBuffer implementation.
 * 
 * These tests are disabled by default to avoid running during regular builds.
 * Enable them when you need to measure performance.
 */
@Disabled("Performance tests - enable manually when needed")
class RingBufferPerformanceTest {
    
    @Test
    fun `measure single producer throughput with different wait strategies`() {
        val strategies = mapOf(
            "BusySpin" to BusySpinWaitStrategy(),
            "Yielding" to YieldingWaitStrategy(),
            "Sleeping" to SleepingWaitStrategy(100_000L),
            "Blocking" to BlockingWaitStrategy()
        )
        
        val bufferSize = 65536
        val messageCount = 10_000_000
        
        strategies.forEach { (name, strategy) ->
            val buffer = RingBufferImpl(bufferSize, strategy)
            val record = createTestRecord()
            
            val timeMillis = measureTimeMillis {
                repeat(messageCount) {
                    buffer.publish(record)
                }
            }
            
            val throughput = messageCount.toDouble() / (timeMillis / 1000.0)
            println("$name Wait Strategy: ${throughput.toInt()} msg/sec (${timeMillis}ms for $messageCount messages)")
            
            // Assert minimum performance threshold
            assertTrue(throughput > 100_000, "$name strategy should achieve >100K msg/sec")
        }
    }
    
    @Test
    fun `measure batch publishing throughput`() {
        val buffer = RingBufferImpl(65536, YieldingWaitStrategy())
        val batchSizes = listOf(10, 100, 1000)
        val totalMessages = 1_000_000
        
        batchSizes.forEach { batchSize ->
            val batches = totalMessages / batchSize
            val records = (1..batchSize).map { createTestRecord() }
            
            val timeMillis = measureTimeMillis {
                repeat(batches) {
                    buffer.publishBatch(records)
                }
            }
            
            val throughput = totalMessages.toDouble() / (timeMillis / 1000.0)
            println("Batch size $batchSize: ${throughput.toInt()} msg/sec")
            
            // Larger batches should achieve better throughput
            assertTrue(throughput > 200_000, "Batch publishing should achieve >200K msg/sec")
        }
    }
    
    @Test
    fun `measure producer consumer latency`() = runBlocking {
        val buffer = RingBufferImpl(1024, YieldingWaitStrategy())
        val consumerSequence = SequenceImpl(-1L)
        buffer.addGatingSequence(consumerSequence)
        
        val barrier = buffer.newBarrier()
        val latencies = mutableListOf<Long>()
        val messageCount = 100_000
        val latch = CountDownLatch(messageCount)
        
        // Consumer coroutine
        val consumer = async(Dispatchers.Default) {
            var nextSequence = 0L
            while (nextSequence < messageCount) {
                try {
                    val availableSequence = barrier.waitFor(nextSequence)
                    
                    for (seq in nextSequence..availableSequence) {
                        val record = buffer.get(seq)
                        if (record != null) {
                            val latencyNanos = System.nanoTime() - record.createdAt
                            latencies.add(latencyNanos)
                            latch.countDown()
                        }
                    }
                    
                    consumerSequence.set(availableSequence)
                    nextSequence = availableSequence + 1
                } catch (e: Exception) {
                    break
                }
            }
        }
        
        // Producer
        repeat(messageCount) {
            val record = createTestRecord().copy(
                createdAt = System.nanoTime() // Use nanoTime for latency measurement
            )
            buffer.publish(record)
            
            // Small delay to simulate realistic publishing
            if (it % 1000 == 0) {
                yield()
            }
        }
        
        latch.await()
        consumer.cancel()
        
        // Calculate latency percentiles
        latencies.sort()
        val p50 = latencies[latencies.size / 2] / 1_000 // Convert to microseconds
        val p99 = latencies[(latencies.size * 0.99).toInt()] / 1_000
        val p999 = latencies[(latencies.size * 0.999).toInt()] / 1_000
        
        println("Latency - P50: ${p50}μs, P99: ${p99}μs, P99.9: ${p999}μs")
        
        // Assert latency thresholds
        assertTrue(p50 < 100, "P50 latency should be < 100μs")
        assertTrue(p99 < 1000, "P99 latency should be < 1ms")
    }
    
    @Test
    fun `measure multi producer throughput`() = runBlocking {
        val buffer = RingBufferImpl(65536, YieldingWaitStrategy())
        val producerCount = 4
        val messagesPerProducer = 1_000_000
        val totalMessages = producerCount * messagesPerProducer
        val publishedCount = AtomicLong(0)
        
        val timeMillis = measureTimeMillis {
            val producers = (1..producerCount).map { producerId ->
                async(Dispatchers.Default) {
                    val record = createTestRecord("producer-$producerId")
                    repeat(messagesPerProducer) {
                        buffer.publish(record)
                        publishedCount.incrementAndGet()
                    }
                }
            }
            
            producers.awaitAll()
        }
        
        val throughput = totalMessages.toDouble() / (timeMillis / 1000.0)
        println("Multi-producer ($producerCount producers): ${throughput.toInt()} msg/sec")
        
        assertEquals(totalMessages.toLong(), publishedCount.get())
        assertTrue(throughput > 500_000, "Multi-producer should achieve >500K msg/sec")
    }
    
    @Test
    fun `measure memory efficiency`() {
        val bufferSizes = listOf(1024, 8192, 65536)
        
        bufferSizes.forEach { size ->
            val runtime = Runtime.getRuntime()
            runtime.gc()
            Thread.sleep(100)
            
            val memoryBefore = runtime.totalMemory() - runtime.freeMemory()
            
            val buffer = RingBufferImpl(size, YieldingWaitStrategy())
            
            // Fill the buffer
            repeat(size) {
                buffer.publish(createTestRecord())
            }
            
            runtime.gc()
            Thread.sleep(100)
            
            val memoryAfter = runtime.totalMemory() - runtime.freeMemory()
            val memoryUsed = memoryAfter - memoryBefore
            val memoryPerSlot = memoryUsed / size
            
            println("Buffer size $size: ${memoryUsed / 1024}KB total, ${memoryPerSlot}B per slot")
            
            // Assert reasonable memory usage
            assertTrue(memoryPerSlot < 1024, "Should use less than 1KB per slot")
        }
    }
    
    private fun createTestRecord(id: String = "test"): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key",
            originalTopic = "topic",
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
    
    private fun assertEquals(expected: Long, actual: Long) {
        kotlin.test.assertEquals(expected, actual)
    }
}