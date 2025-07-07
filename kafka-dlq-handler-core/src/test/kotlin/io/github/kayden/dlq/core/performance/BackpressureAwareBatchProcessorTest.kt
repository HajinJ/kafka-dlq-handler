package io.github.kayden.dlq.core.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.core.storage.InMemoryDLQStorage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asFlow
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class BackpressureAwareBatchProcessorTest : FunSpec({
    
    test("should process messages with no backpressure") {
        val storage = InMemoryDLQStorage()
        val processedCount = AtomicInteger(0)
        
        val processor = backpressureAwareBatchProcessor {
            storage(storage)
            recordHandler { record ->
                processedCount.incrementAndGet()
                Result.success(Unit)
            }
            backpressureStrategy(ThresholdBackpressureStrategy())
            configuration(BatchConfiguration(
                maxBatchSize = 10,
                maxWaitTime = Duration.ofMillis(100)
            ))
        }
        
        processor.start()
        
        // Process some records
        val records = (1..50).map { createTestRecord(it) }
        val result = processor.processBatch(records)
        
        delay(200) // Allow processing
        
        result.processed shouldBe 50
        result.failed shouldBe 0
        processedCount.get() shouldBe 50
        
        processor.stop(Duration.ofSeconds(1))
    }
    
    test("should apply backpressure when system is overloaded") {
        val storage = InMemoryDLQStorage()
        var acceptedCount = 0
        var rejectedCount = 0
        
        val processor = BackpressureAwareBatchProcessor(
            storage = storage,
            recordHandler = { Result.success(Unit) },
            backpressureStrategy = object : BackpressureStrategy {
                override fun shouldAccept(currentLoad: LoadMetrics): Boolean {
                    // Reject everything after 10 messages
                    return acceptedCount < 10
                }
                override fun onAccepted() { acceptedCount++ }
                override fun onRejected() { rejectedCount++ }
                override fun onCompleted(success: Boolean, duration: Duration) {}
                override fun getMetrics() = BackpressureMetrics(
                    acceptedCount.toLong(), rejectedCount.toLong(), 0.0, 0.0, Instant.now()
                )
                override fun reset() {}
            }
        )
        
        processor.start()
        
        // Try to process 20 records
        val records = (1..20).map { createTestRecord(it) }
        val result = processor.processBatch(records)
        
        delay(200)
        
        // Should only process first 10
        result.processed shouldBeLessThan 11
        acceptedCount shouldBe 10
        rejectedCount shouldBeGreaterThan 0
        
        processor.stop(Duration.ofSeconds(1))
    }
    
    test("should integrate with rate limiter") {
        val storage = InMemoryDLQStorage()
        val rateLimiter = TokenBucketRateLimiter(10.0, 5) // 10/sec, burst 5
        
        val processor = backpressureAwareBatchProcessor {
            storage(storage)
            recordHandler { Result.success(Unit) }
            rateLimiter(rateLimiter)
            configuration(BatchConfiguration(maxBatchSize = 100))
        }
        
        processor.start()
        
        // Try to process more than rate limit
        val records = (1..20).map { createTestRecord(it) }
        
        repeat(2) {
            processor.processBatch(records)
        }
        
        delay(200)
        
        val metrics = rateLimiter.getMetrics()
        // Should be limited by rate limiter
        metrics.acquiredPermits shouldBeLessThan 15 // Initial burst + some refill
        metrics.rejectedPermits shouldBeGreaterThan 0L
        
        processor.stop(Duration.ofSeconds(1))
    }
    
    test("should integrate with circuit breaker") {
        val storage = InMemoryDLQStorage()
        val circuitBreaker = DefaultCircuitBreaker(
            CircuitBreakerConfig(failureThreshold = 3)
        )
        var failureCount = 0
        
        val processor = backpressureAwareBatchProcessor {
            storage(storage)
            recordHandler { record ->
                // Fail first 5 requests
                if (failureCount++ < 5) {
                    Result.failure(Exception("Test failure"))
                } else {
                    Result.success(Unit)
                }
            }
            circuitBreaker(circuitBreaker)
        }
        
        processor.start()
        
        // Process records that will fail
        val records = (1..10).map { createTestRecord(it) }
        processor.processBatch(records)
        
        delay(200)
        
        // Circuit should be open
        circuitBreaker.getState() shouldBe CircuitBreakerState.OPEN
        
        // Further requests should be rejected
        val moreRecords = (11..20).map { createTestRecord(it) }
        processor.processBatch(moreRecords)
        
        delay(100)
        
        val bpMetrics = processor.getBackpressureMetrics()
        bpMetrics.rejectedMessages shouldBeGreaterThan 0
        
        processor.stop(Duration.ofSeconds(1))
    }
    
    test("should handle flow processing with backpressure") {
        val storage = InMemoryDLQStorage()
        val processedIds = mutableSetOf<Long>()
        
        val processor = backpressureAwareBatchProcessor {
            storage(storage)
            recordHandler { record ->
                processedIds.add(record.id)
                Result.success(Unit)
            }
            backpressureStrategy(AdaptiveBackpressureStrategy())
            configuration(BatchConfiguration(
                maxBatchSize = 5,
                maxWaitTime = Duration.ofMillis(50)
            ))
        }
        
        processor.start()
        
        // Process flow
        val recordFlow = (1..30).map { createTestRecord(it) }.asFlow()
        
        runBlocking {
            processor.processFlow(recordFlow) { batchResult ->
                // Batch complete callback
            }
        }
        
        delay(500)
        
        // Should process all eventually
        processedIds.size shouldBeGreaterThan 20
        
        processor.stop(Duration.ofSeconds(1))
    }
    
    test("should retry rejected messages with backoff") {
        val storage = InMemoryDLQStorage()
        var attemptCount = 0
        val processedIds = mutableSetOf<Long>()
        
        val processor = BackpressureAwareBatchProcessor(
            storage = storage,
            recordHandler = { record ->
                processedIds.add(record.id)
                Result.success(Unit)
            },
            backpressureStrategy = object : BackpressureStrategy {
                override fun shouldAccept(currentLoad: LoadMetrics): Boolean {
                    // Accept after 3 attempts
                    return ++attemptCount > 3
                }
                override fun onAccepted() {}
                override fun onRejected() {}
                override fun onCompleted(success: Boolean, duration: Duration) {}
                override fun getMetrics() = BackpressureMetrics(0, 0, 0.0, 0.0, Instant.now())
                override fun reset() {}
            }
        )
        
        processor.start()
        
        val recordFlow = listOf(createTestRecord(1)).asFlow()
        
        runBlocking {
            processor.processFlow(recordFlow)
        }
        
        delay(1000) // Allow retries with backoff
        
        // Should eventually process after retries
        processedIds.size shouldBe 1
        attemptCount shouldBeGreaterThan 3
        
        processor.stop(Duration.ofSeconds(1))
    }
    
    test("should track comprehensive metrics") {
        val storage = InMemoryDLQStorage()
        val rateLimiter = TokenBucketRateLimiter(50.0, 10)
        val circuitBreaker = DefaultCircuitBreaker()
        
        val processor = backpressureAwareBatchProcessor {
            storage(storage)
            recordHandler { Result.success(Unit) }
            backpressureStrategy(AdaptiveBackpressureStrategy())
            rateLimiter(rateLimiter)
            circuitBreaker(circuitBreaker)
        }
        
        processor.start()
        
        // Process some records
        val records = (1..30).map { createTestRecord(it) }
        processor.processBatch(records)
        
        delay(500)
        
        val metrics = processor.getBackpressureMetrics()
        
        // Should have comprehensive metrics
        metrics.batchMetrics.totalProcessed shouldBeGreaterThan 0
        metrics.backpressureMetrics.totalAccepted shouldBeGreaterThan 0
        metrics.rateLimiterMetrics?.acquiredPermits shouldBeGreaterThan 0
        metrics.circuitBreakerMetrics?.state shouldBe CircuitBreakerState.CLOSED
        metrics.acceptedMessages shouldBeGreaterThan 0
        metrics.overallAcceptanceRate shouldBeGreaterThan 0.0
        
        processor.stop(Duration.ofSeconds(1))
    }
    
    test("should handle high load scenarios") {
        val storage = InMemoryDLQStorage()
        val processedCount = AtomicInteger(0)
        
        val processor = backpressureAwareBatchProcessor {
            storage(storage)
            recordHandler { record ->
                processedCount.incrementAndGet()
                delay(1) // Simulate processing time
                Result.success(Unit)
            }
            backpressureStrategy(AdaptiveBackpressureStrategy())
            rateLimiter(TokenBucketRateLimiter(100.0, 50))
            configuration(BatchConfiguration(
                maxBatchSize = 20,
                maxWaitTime = Duration.ofMillis(50),
                parallelism = 4
            ))
        }
        
        processor.start()
        
        // Generate high load
        val jobs = List(5) { batchId ->
            GlobalScope.launch {
                val records = (1..100).map { createTestRecord(batchId * 100L + it) }
                processor.processBatch(records)
            }
        }
        
        runBlocking {
            jobs.forEach { it.join() }
            delay(2000) // Allow processing
        }
        
        val metrics = processor.getBackpressureMetrics()
        
        // Should handle load with backpressure
        processedCount.get() shouldBeGreaterThan 0
        metrics.rejectedMessages shouldBeGreaterThan 0 // Some should be rejected
        metrics.overallAcceptanceRate shouldBeLessThan 100.0
        
        processor.stop(Duration.ofSeconds(2))
    }
    
    test("should update configuration dynamically") {
        val storage = InMemoryDLQStorage()
        
        val processor = backpressureAwareBatchProcessor {
            storage(storage)
            recordHandler { Result.success(Unit) }
            configuration(BatchConfiguration(maxBatchSize = 10))
        }
        
        processor.start()
        
        processor.getConfiguration().maxBatchSize shouldBe 10
        
        // Update configuration
        processor.updateConfiguration(BatchConfiguration(maxBatchSize = 20))
        
        processor.getConfiguration().maxBatchSize shouldBe 20
        
        processor.stop(Duration.ofSeconds(1))
    }
    
    test("should integrate all components seamlessly") {
        val storage = InMemoryDLQStorage()
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        
        val processor = backpressureAwareBatchProcessor {
            storage(storage)
            recordHandler { record ->
                // Simulate mixed results
                if (record.id % 10 == 0L) {
                    failureCount.incrementAndGet()
                    Result.failure(Exception("Test failure"))
                } else {
                    successCount.incrementAndGet()
                    Result.success(Unit)
                }
            }
            backpressureStrategy(AdaptiveBackpressureStrategy(
                BackpressureConfig(
                    maxPendingMessages = 100,
                    maxMemoryUsage = 80.0,
                    maxCpuUsage = 80.0,
                    maxErrorRate = 0.2
                )
            ))
            rateLimiter(TokenBucketRateLimiter(50.0, 20))
            circuitBreaker(DefaultCircuitBreaker(
                CircuitBreakerConfig(
                    failureThreshold = 10,
                    failureRateThreshold = 0.3
                )
            ))
            configuration(BatchConfiguration(
                maxBatchSize = 15,
                maxWaitTime = Duration.ofMillis(100),
                parallelism = 3
            ))
        }
        
        processor.start()
        
        // Process multiple batches
        repeat(3) { batch ->
            val records = (1..50).map { createTestRecord(batch * 50L + it) }
            GlobalScope.launch {
                processor.processBatch(records)
            }
            delay(100)
        }
        
        delay(2000) // Allow processing
        
        val metrics = processor.getBackpressureMetrics()
        
        // Verify integration
        successCount.get() shouldBeGreaterThan 0
        failureCount.get() shouldBeGreaterThan 0
        metrics.batchMetrics.totalProcessed shouldBeGreaterThan 0
        metrics.backpressureMetrics.totalAccepted shouldBeGreaterThan 0
        metrics.rateLimiterMetrics?.acquiredPermits shouldBeGreaterThan 0
        
        // Circuit breaker should still be functional
        val cbMetrics = metrics.circuitBreakerMetrics
        cbMetrics?.totalRequests shouldBeGreaterThan 0
        
        processor.stop(Duration.ofSeconds(2))
    }
})

private fun createTestRecord(id: Long): DLQRecord {
    return DLQRecord(
        id = id,
        topic = "test-topic",
        partition = (id % 3).toInt(),
        offset = id * 100,
        key = "key-$id".toByteArray(),
        value = "value-$id".toByteArray(),
        headers = mapOf("header" to "value"),
        errorType = ErrorType.TRANSIENT_NETWORK_ERROR,
        errorMessage = "Test error",
        exceptionStackTrace = null,
        processingAttempts = 1,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}