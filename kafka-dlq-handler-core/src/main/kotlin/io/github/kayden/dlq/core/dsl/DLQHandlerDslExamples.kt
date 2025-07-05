package io.github.kayden.dlq.core.dsl

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.ErrorType
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration


/**
 * Examples demonstrating the Kafka DLQ Handler DSL usage.
 * 
 * @since 0.1.0
 */
object DLQHandlerDslExamples {
    
    /**
     * Example 1: Simple handler with default configuration.
     */
    fun simpleHandler() = runBlocking {
        val handler = dlqHandler {
            // All defaults - uses in-memory storage, batch processing
        }
        
        handler.start()
        
        // Process records
        val record = DLQRecord(
            id = "123",
            messageKey = "key-123",
            originalTopic = "orders",
            originalPartition = 0,
            originalOffset = 100,
            payload = """{"orderId": 123, "amount": 99.99}""".toByteArray(),
            headers = mapOf("contentType" to "application/json".toByteArray()),
            errorClass = "ProcessingException",
            errorMessage = "Order validation failed",
            errorType = ErrorType.VALIDATION_ERROR,
            stackTrace = null
        )
        
        handler.process(record)
        handler.stop()
    }
    
    /**
     * Example 2: High-throughput configuration for processing 100K+ messages/second.
     */
    fun highThroughputHandler() = runBlocking {
        val handler = dlqHandler {
            performance {
                highThroughput() // Preset for high throughput
                
                // Additional customization
                parallel {
                    workers = 32
                    partitions = 128
                }
                
                buffer {
                    size(256, SizeUnit.KB)
                }
            }
            
            storage {
                inMemory {
                    maxSize = 1_000_000
                    evictionPolicy = EvictionPolicy.LRU
                }
            }
        }
        
        handler.start()
        // Process massive amount of records...
        handler.stop()
    }
    
    /**
     * Example 3: Low-latency configuration for real-time processing.
     */
    fun lowLatencyHandler() = runBlocking {
        val handler = dlqHandler {
            performance {
                lowLatency() // Preset for low latency
                
                batch {
                    size = 10
                    timeout(5.let { Duration.milliseconds(it.toLong()) })
                }
            }
            
            processing {
                timeout = Duration.milliseconds(100)
                
                processor { record ->
                    // Fast processing logic
                    Result.success(Unit)
                }
            }
        }
        
        handler.start()
        // Process time-sensitive records...
        handler.stop()
    }
    
    /**
     * Example 4: Advanced backpressure and rate limiting.
     */
    fun backpressureHandler() = runBlocking {
        val handler = dlqHandler {
            performance {
                mode = ProcessingMode.PARALLEL
                
                backpressure {
                    enabled = true
                    maxPending = 500_000
                    
                    // Adaptive strategy adjusts acceptance rate based on load
                    adaptive {
                        initialRate = 1.0
                        minRate = 0.1
                        threshold = 0.8
                    }
                    
                    // Rate limiting to prevent overwhelming downstream
                    rateLimit {
                        tokenBucket(rate = 50_000, burst = 100_000)
                    }
                    
                    // Circuit breaker for fault tolerance
                    circuitBreaker {
                        failureThreshold = 10
                        failureRateThreshold = 0.5
                        timeout(Duration.seconds(30))
                        halfOpenRequests = 5
                    }
                }
            }
        }
        
        handler.start()
        // Handle traffic spikes gracefully...
        handler.stop()
    }
    
    /**
     * Example 5: Custom retry policies and error handling.
     */
    fun retryAndErrorHandler() = runBlocking {
        val handler = dlqHandler {
            processing {
                retry {
                    maxAttempts = 5
                    
                    // Exponential backoff with jitter
                    exponential {
                        initialDelay = Duration.seconds(1)
                        maxDelay = Duration.minutes(5)
                        multiplier = 2.0
                        withJitter = true
                    }
                    
                    // Retry only on specific exceptions
                    retryOn(TransientException::class.java)
                    retryOn(NetworkException::class.java)
                    
                    // Skip retry for permanent failures
                    skipOn(ValidationException::class.java)
                    skipOn(AuthenticationException::class.java)
                }
                
                processor { record ->
                    // Processing logic that might fail
                    processRecord(record)
                }
            }
            
            errorHandling {
                // Handle specific error types
                on(NetworkException::class.java) { record, error ->
                    // Log and possibly alert
                    println("Network error for record ${record.id}: ${error.message}")
                }
                
                on(ValidationException::class.java) { record, error ->
                    // Move to permanent failure queue
                    println("Validation failed for ${record.id}")
                }
                
                // Default handler for unhandled errors
                default { record, error ->
                    println("Unexpected error for ${record.id}: $error")
                }
            }
        }
        
        handler.start()
        // Process with sophisticated error handling...
        handler.stop()
    }
    
    /**
     * Example 6: External storage configuration (Redis example).
     */
    fun externalStorageHandler() = runBlocking {
        val handler = dlqHandler {
            storage {
                redis {
                    connection {
                        host = "redis.example.com"
                        port = 6379
                        password = "secret"
                        database = 1
                    }
                    
                    pool {
                        size = 20
                        timeout(Duration.seconds(5))
                    }
                    
                    // Auto-expire old messages
                    expireKeys(Duration.days(7))
                    keyPrefix = "dlq:production:"
                }
            }
            
            performance {
                // Redis works well with batch operations
                batch {
                    size = 100
                    timeout(Duration.milliseconds(50))
                }
            }
        }
        
        handler.start()
        // Process with Redis storage...
        handler.stop()
    }
    
    /**
     * Example 7: Database storage with batch optimizations.
     */
    fun databaseStorageHandler() = runBlocking {
        val handler = dlqHandler {
            storage {
                database {
                    connection {
                        postgresql(
                            host = "db.example.com",
                            port = 5432,
                            database = "dlq_db"
                        )
                        username = "dlq_user"
                        password = "secure_password"
                    }
                    
                    table("dlq_messages", schema = "messaging")
                    batching(size = 500) // Batch inserts for performance
                }
            }
            
            performance {
                batch {
                    size = 500 // Match database batch size
                    timeout(Duration.milliseconds(200))
                }
            }
        }
        
        handler.start()
        // Process with database storage...
        handler.stop()
    }
    
    /**
     * Example 8: Custom storage implementation.
     */
    fun customStorageHandler() = runBlocking {
        val handler = dlqHandler {
            storage {
                custom { config ->
                    // Create your custom storage implementation
                    MyCustomStorage(
                        endpoint = config.getString("endpoint", "http://localhost:9000"),
                        bucket = config.getString("bucket", "dlq-messages"),
                        accessKey = config.getString("accessKey"),
                        secretKey = config.getString("secretKey")
                    )
                }
            }
        }
        
        handler.start()
        // Process with custom storage...
        handler.stop()
    }
    
    /**
     * Example 9: Monitoring and metrics configuration.
     */
    fun monitoredHandler() = runBlocking {
        val handler = dlqHandler {
            metrics {
                enabled = true
                interval(Duration.seconds(10))
                includeHistograms = true
                includePercentiles = true
                
                tags(
                    "environment" to "production",
                    "service" to "order-processor",
                    "region" to "us-east-1"
                )
            }
            
            performance {
                mode = ProcessingMode.HYBRID
            }
        }
        
        handler.start()
        
        // Periodically check metrics
        repeat(10) {
            delay(1000)
            val metrics = handler.getMetrics()
            println("Processed: ${metrics.totalProcessed}, Errors: ${metrics.totalErrors}")
        }
        
        handler.stop()
    }
    
    /**
     * Example 10: Complete production configuration.
     */
    fun productionHandler() = runBlocking {
        val handler = dlqHandler {
            performance {
                mode = ProcessingMode.HYBRID
                
                batch {
                    size = 1000
                    minSize = 100
                    timeout(Duration.milliseconds(100))
                    adaptive = true
                }
                
                parallel {
                    workers = 16
                    partitions = 64
                    workStealing = true
                    cpuAffinity = true
                }
                
                backpressure {
                    enabled = true
                    maxPending = 1_000_000
                    
                    adaptive {
                        threshold = 0.85
                    }
                    
                    rateLimit {
                        tokenBucket(rate = 100_000, burst = 200_000)
                    }
                    
                    circuitBreaker {
                        failureThreshold = 20
                        failureRateThreshold = 0.3
                        timeout(Duration.minutes(1))
                        slidingWindowSize = 1000
                    }
                }
            }
            
            storage {
                database {
                    connection {
                        postgresql("prod-db.internal", database = "messaging")
                        username = System.getenv("DB_USER")
                        password = System.getenv("DB_PASSWORD")
                    }
                    
                    table("dlq_messages")
                    batching(1000)
                }
            }
            
            processing {
                retry {
                    maxAttempts = 5
                    exponential {
                        initialDelay = Duration.seconds(1)
                        maxDelay = Duration.minutes(5)
                        withJitter = true
                    }
                }
                
                timeout = Duration.seconds(30)
                
                processor { record ->
                    // Actual message processing
                    when (record.originalTopic) {
                        "orders" -> processOrder(record)
                        "payments" -> processPayment(record)
                        "inventory" -> processInventory(record)
                        else -> Result.failure(Exception("Unknown topic"))
                    }
                }
                
                deduplication {
                    enabled = true
                    window(Duration.minutes(10))
                    keyExtractor = { "${it.originalTopic}:${it.messageKey}" }
                }
            }
            
            errorHandling {
                on(TransientException::class.java) { record, error ->
                    // Will be retried automatically
                }
                
                on(PermanentException::class.java) { record, error ->
                    // Send to permanent failure topic
                    alertOpsTeam(record, error)
                }
                
                default { record, error ->
                    // Log unexpected errors
                    logger.severe("Unexpected error processing ${record.id}: $error")
                }
            }
            
            metrics {
                enabled = true
                interval(Duration.seconds(30))
                
                tags(
                    "env" to "production",
                    "service" to "dlq-processor",
                    "version" to "1.0.0"
                )
            }
        }
        
        handler.start()
        // Production processing...
        handler.stop()
    }
    
    // Helper functions for examples
    private suspend fun processRecord(record: DLQRecord): Result<Unit> = Result.success(Unit)
    private suspend fun processOrder(record: DLQRecord): Result<Unit> = Result.success(Unit)
    private suspend fun processPayment(record: DLQRecord): Result<Unit> = Result.success(Unit)
    private suspend fun processInventory(record: DLQRecord): Result<Unit> = Result.success(Unit)
    private fun alertOpsTeam(record: DLQRecord, error: Throwable) {}
    private suspend fun delay(ms: Long) = kotlinx.coroutines.delay(ms)
    
    // Example custom storage implementation
    class MyCustomStorage(
        private val endpoint: String,
        private val bucket: String,
        private val accessKey: String,
        private val secretKey: String
    ) : io.github.kayden.dlq.core.storage.DLQStorage {
        override suspend fun save(record: DLQRecord) = 
            io.github.kayden.dlq.core.storage.StorageResult.Success(record.id)
        override suspend fun findById(id: String): DLQRecord? = null
        override fun findByCriteria(criteria: io.github.kayden.dlq.core.storage.QueryCriteria) = 
            kotlinx.coroutines.flow.emptyFlow<DLQRecord>()
        override fun findByStatus(status: io.github.kayden.dlq.core.model.DLQStatus, limit: Int) = 
            kotlinx.coroutines.flow.emptyFlow<DLQRecord>()
        override fun findRetryableRecords(limit: Int) = 
            kotlinx.coroutines.flow.emptyFlow<DLQRecord>()
        override suspend fun update(record: DLQRecord) = 
            io.github.kayden.dlq.core.storage.StorageResult.Success(record.id)
        override suspend fun delete(id: String) = 
            io.github.kayden.dlq.core.storage.StorageResult.Success(id)
        override suspend fun deleteByCriteria(criteria: io.github.kayden.dlq.core.storage.QueryCriteria) = 0
        override suspend fun deleteOldRecords(beforeTimestamp: Long, statuses: Set<io.github.kayden.dlq.core.model.DLQStatus>) = 0
        override suspend fun count(status: io.github.kayden.dlq.core.model.DLQStatus?) = 0L
        override suspend fun clear() = Unit
        override suspend fun getStatistics() = io.github.kayden.dlq.core.storage.StorageStatistics(
            totalRecords = 0,
            recordsByStatus = emptyMap(),
            oldestRecordTimestamp = null,
            newestRecordTimestamp = null,
            averageRecordSize = 0L
        )
        override suspend fun isHealthy() = true
        override suspend fun saveAll(records: List<DLQRecord>) = 
            records.map { io.github.kayden.dlq.core.storage.StorageResult.Success(it.id) }
        override suspend fun updateAll(records: List<DLQRecord>) = 
            records.map { io.github.kayden.dlq.core.storage.StorageResult.Success(it.id) }
    }
    
    // Example exception types
    class TransientException(message: String) : Exception(message)
    class NetworkException(message: String) : Exception(message)
    class ValidationException(message: String) : Exception(message)
    class AuthenticationException(message: String) : Exception(message)
    class PermanentException(message: String) : Exception(message)
    
    private val logger = java.util.logging.Logger.getLogger("DLQHandler")
}

