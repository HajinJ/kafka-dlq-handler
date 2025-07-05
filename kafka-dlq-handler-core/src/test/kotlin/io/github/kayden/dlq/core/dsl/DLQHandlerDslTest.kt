package io.github.kayden.dlq.core.dsl

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.core.model.BackoffStrategy
import io.github.kayden.dlq.core.performance.AdaptiveBackpressureStrategy
import io.github.kayden.dlq.core.performance.TokenBucketRateLimiter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.*

class DLQHandlerDslTest {
    
    @Test
    fun `should create simple DLQ handler with defaults`() = runTest {
        val handler = dlqHandler {
            // Uses all defaults
        }
        
        assertNotNull(handler)
        
        handler.start()
        val metrics = handler.getMetrics()
        
        assertEquals(0L, metrics.totalProcessed)
        assertEquals(0L, metrics.totalErrors)
        
        handler.stop()
    }
    
    @Test
    fun `should create handler with performance configuration`() = runTest {
        val handler = dlqHandler {
            performance {
                mode = ProcessingMode.PARALLEL
                
                batch {
                    size = 5000
                    timeout = 200.milliseconds
                    adaptive = true
                }
                
                parallel {
                    workers = 8
                    partitions = 32
                    workStealing = true
                }
            }
        }
        
        assertNotNull(handler)
        handler.start()
        handler.stop()
    }
    
    @Test
    fun `should create handler with high throughput preset`() = runTest {
        val handler = dlqHandler {
            performance {
                highThroughput()
            }
        }
        
        assertNotNull(handler)
        handler.start()
        
        // Should be able to process records
        val record = createTestRecord()
        val result = handler.process(record)
        
        assertTrue(result.isSuccess)
        
        handler.stop()
    }
    
    @Test
    fun `should create handler with low latency preset`() = runTest {
        val handler = dlqHandler {
            performance {
                lowLatency()
            }
        }
        
        assertNotNull(handler)
        handler.start()
        handler.stop()
    }
    
    @Test
    fun `should configure backpressure with adaptive strategy`() = runTest {
        val handler = dlqHandler {
            performance {
                backpressure {
                    enabled = true
                    maxPending = 500_000
                    
                    adaptive {
                        initialRate = 0.8
                        threshold = 0.85
                    }
                }
            }
        }
        
        assertNotNull(handler)
        handler.start()
        handler.stop()
    }
    
    @Test
    fun `should configure rate limiting`() = runTest {
        val handler = dlqHandler {
            performance {
                backpressure {
                    rateLimit {
                        tokenBucket(rate = 10000, burst = 20000)
                    }
                }
            }
        }
        
        assertNotNull(handler)
        handler.start()
        handler.stop()
    }
    
    @Test
    fun `should configure circuit breaker`() = runTest {
        val handler = dlqHandler {
            performance {
                backpressure {
                    circuitBreaker {
                        failureThreshold = 10
                        timeout = 30.seconds
                        halfOpenRequests = 5
                    }
                }
            }
        }
        
        assertNotNull(handler)
        handler.start()
        handler.stop()
    }
    
    @Test
    fun `should configure storage options`() = runTest {
        val handler = dlqHandler {
            storage {
                inMemory {
                    maxSize = 100_000
                    evictionPolicy = EvictionPolicy.LRU
                    ttl = 30.minutes
                }
            }
        }
        
        assertNotNull(handler)
        handler.start()
        handler.stop()
    }
    
    @Test
    fun `should configure processing with retry`() = runTest {
        var processedCount = 0
        
        val handler = dlqHandler {
            processing {
                retry {
                    maxAttempts = 3
                    exponential {
                        initialDelay = 1.seconds
                        maxDelay = 30.seconds
                        withJitter = true
                    }
                }
                
                timeout = 5.minutes
                
                processor { record ->
                    processedCount++
                    Result.success(Unit)
                }
            }
        }
        
        handler.start()
        
        val record = createTestRecord()
        val result = handler.process(record)
        
        assertTrue(result.isSuccess)
        assertEquals(1, processedCount)
        
        handler.stop()
    }
    
    @Test
    fun `should configure error handling`() = runTest {
        var errorHandled = false
        
        val handler = dlqHandler {
            processing {
                processor { record ->
                    Result.failure(RuntimeException("Test error"))
                }
            }
            
            errorHandling {
                on<RuntimeException> { record, error ->
                    errorHandled = true
                }
            }
        }
        
        handler.start()
        
        val record = createTestRecord()
        val result = handler.process(record)
        
        assertTrue(result.isFailure)
        assertTrue(errorHandled)
        
        handler.stop()
    }
    
    @Test
    fun `should process batch of records`() = runTest {
        var processedCount = 0
        
        val handler = dlqHandler {
            performance {
                mode = ProcessingMode.BATCH
                batch {
                    size = 10
                    timeout = 100.milliseconds
                }
            }
            
            processing {
                processor { record ->
                    processedCount++
                    Result.success(Unit)
                }
            }
        }
        
        handler.start()
        
        val records = List(10) { createTestRecord() }
        val result = handler.processBatch(records)
        
        assertEquals(10, result.successful)
        assertEquals(0, result.failed)
        assertEquals(10, processedCount)
        
        handler.stop()
    }
    
    @Test
    fun `should handle hybrid processing mode`() = runTest {
        val handler = dlqHandler {
            performance {
                mode = ProcessingMode.HYBRID
            }
        }
        
        handler.start()
        
        // Process single record
        val singleResult = handler.process(createTestRecord())
        assertTrue(singleResult.isSuccess)
        
        // Process batch
        val records = List(100) { createTestRecord() }
        val batchResult = handler.processBatch(records)
        assertEquals(100, batchResult.successful)
        
        handler.stop()
    }
    
    @Test
    fun `should collect metrics`() = runTest {
        val handler = dlqHandler {
            metrics {
                enabled = true
                interval = 1.seconds
            }
        }
        
        handler.start()
        
        // Process some records
        repeat(5) {
            handler.process(createTestRecord())
        }
        
        val metrics = handler.getMetrics()
        assertEquals(5L, metrics.totalProcessed)
        assertEquals(0L, metrics.totalErrors)
        
        handler.stop()
    }
    
    @Test
    fun `should support fluent API chaining`() = runTest {
        val handler = dlqHandler {
            performance {
                mode = ProcessingMode.PARALLEL
                parallel {
                    workers = 4
                    workStealing = true
                }
                backpressure {
                    enabled = true
                    adaptive()
                    rateLimit {
                        tokenBucket(1000)
                    }
                }
            }
            
            storage {
                inMemory {
                    maxSize = 10000
                    evictionPolicy = EvictionPolicy.LRU
                }
            }
            
            processing {
                retry {
                    maxAttempts = 3
                    exponential()
                }
                processor { Result.success(Unit) }
            }
        }
        
        assertNotNull(handler)
        handler.start()
        handler.stop()
    }
    
    private fun createTestRecord(): DLQRecord {
        return DLQRecord(
            id = UUID.randomUUID().toString(),
            messageKey = "test-key",
            originalTopic = "test-topic",
            originalPartition = 0,
            originalOffset = 0L,
            payload = "test-payload".toByteArray(),
            headers = emptyMap(),
            errorClass = "TestError",
            errorMessage = "Test error message",
            errorType = ErrorType.TRANSIENT_NETWORK_ERROR,
            stackTrace = null,
            status = DLQStatus.PENDING,
            retryCount = 0
        )
    }
}