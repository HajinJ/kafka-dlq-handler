# Kafka DLQ Handler - Technical Specifications (High Performance Edition)

> 이 문서는 대규모 트래픽 처리가 가능한 kafka-dlq-handler의 상세 기술 명세입니다.
> Core 모듈은 프레임워크 독립적이며, 초당 100K+ 메시지 처리를 목표로 설계되었습니다.

## 📅 Last Updated: 2025-06-28

## 🏗️ Architecture Overview

### Design Principles

1. **Framework Agnostic Core**: Core 모듈은 Spring/JPA 의존성 완전 배제
2. **High Performance First**: 대규모 트래픽 처리를 위한 최적화 우선
3. **Zero-Copy Operations**: 불필요한 메모리 복사 최소화
4. **Kotlin Performance Features**: Inline functions, value classes 적극 활용
5. **Pluggable Architecture**: 성능 저하 없는 확장 가능 구조

### Module Structure

```
kafka-dlq-handler/
├── kafka-dlq-handler-core/              # Pure Kotlin, NO Spring/JPA dependencies
│   ├── model/                          # DLQRecord (not JPA entity)
│   ├── processor/                      # High-performance processors
│   ├── storage/                        # Storage interfaces only
│   └── performance/                    # Performance utilities
├── kafka-dlq-handler-spring-boot-starter/   
├── kafka-dlq-handler-storage-jpa/       # JPA implementation (separate module)
├── kafka-dlq-handler-storage-redis/     
├── kafka-dlq-handler-storage-cassandra/ # For high-throughput scenarios
├── kafka-dlq-handler-metrics/           
└── examples/
```

## 🚀 High Performance Features

### Performance Architecture

```kotlin
// Core performance configuration
data class PerformanceConfig(
    val batchSize: Int = 1000,
    val parallelism: Int = Runtime.getRuntime().availableProcessors(),
    val bufferSize: Int = 100_000,
    val ringBufferSize: Int = 65536, // Must be power of 2
    val useZeroCopy: Boolean = true,
    val enableBatching: Boolean = true,
    val backpressureStrategy: BackpressureStrategy = BackpressureStrategy.ADAPTIVE
)

// Backpressure strategies
enum class BackpressureStrategy {
    DROP_OLDEST,      // Drop oldest messages when buffer full
    DROP_NEWEST,      // Drop new messages when buffer full
    BLOCK,           // Block until space available
    ADAPTIVE         // Dynamically adjust based on load
}
```

### Ring Buffer Implementation

```kotlin
// High-performance ring buffer for message queuing
class DLQRingBuffer(size: Int = 65536) {
    private val buffer = AtomicReferenceArray<DLQRecord?>(size)
    private val head = AtomicLong(0)
    private val tail = AtomicLong(0)
    private val cachedHead = AtomicLong(0)
    private val cachedTail = AtomicLong(0)
    
    fun offer(record: DLQRecord): Boolean {
        val currentTail = tail.get()
        val wrapPoint = currentTail - size
        
        if (cachedHead.get() <= wrapPoint) {
            cachedHead.set(head.get())
            if (cachedHead.get() <= wrapPoint) {
                return false // Buffer full
            }
        }
        
        buffer.set((currentTail % size).toInt(), record)
        tail.lazySet(currentTail + 1)
        return true
    }
    
    fun poll(): DLQRecord? {
        val currentHead = head.get()
        if (currentHead >= cachedTail.get()) {
            cachedTail.set(tail.get())
            if (currentHead >= cachedTail.get()) {
                return null // Buffer empty
            }
        }
        
        val index = (currentHead % size).toInt()
        val record = buffer.get(index)
        buffer.set(index, null)
        head.lazySet(currentHead + 1)
        return record
    }
}
```

## 🎯 Core Data Models (Framework Independent)

### DLQRecord (NOT a JPA Entity)

```kotlin
// Pure data class for Core module - NO JPA annotations
data class DLQRecord(
    val id: String = UUID.randomUUID().toString(),
    val messageKey: String?,
    val originalTopic: String,
    val originalPartition: Int,
    val originalOffset: Long,
    val payload: ByteArray,  // Keep as ByteArray for zero-copy
    val headers: Map<String, ByteArray>,
    val errorClass: String?,
    val errorMessage: String?,
    val errorType: ErrorType,
    val stackTrace: String?,
    val status: DLQStatus = DLQStatus.PENDING,
    val retryCount: Int = 0,
    val lastRetryAt: Long? = null,
    val processingMetadata: ProcessingMetadata? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    // Override equals and hashCode for ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DLQRecord) return false
        return id == other.id
    }
    
    override fun hashCode(): Int = id.hashCode()
    
    // Lazy deserialization for performance
    val payloadAsString: String by lazy { 
        String(payload, Charsets.UTF_8) 
    }
    
    val headersAsStringMap: Map<String, String> by lazy {
        headers.mapValues { String(it.value, Charsets.UTF_8) }
    }
}
```

### High-Performance Value Classes

```kotlin
// Use value classes for zero-overhead abstractions
@JvmInline
value class MessageId(val value: String)

@JvmInline
value class TopicName(val value: String)

@JvmInline
value class PartitionId(val value: Int)

// Batch processing result
data class BatchResult(
    val successful: Int,
    val failed: Int,
    val duration: Duration,
    val throughput: Double = successful / duration.toSeconds()
)
```

## 🎨 High-Performance Kotlin DSL

### Performance-Oriented Configuration DSL

```kotlin
// DSL for high-performance configuration
fun dlqHandler(block: DLQHandlerBuilder.() -> Unit): DLQHandler {
    return DLQHandlerBuilder().apply(block).build()
}

class DLQHandlerBuilder {
    private var storage: DLQStorage = InMemoryDLQStorage()
    private var performanceConfig = PerformanceConfig()
    
    fun performance(block: PerformanceConfigBuilder.() -> Unit) {
        performanceConfig = PerformanceConfigBuilder().apply(block).build()
    }
    
    fun storage(block: StorageBuilder.() -> Unit) {
        storage = StorageBuilder().apply(block).build()
    }
}

// Usage
val handler = dlqHandler {
    performance {
        batchSize = 5000
        parallelism = 16
        bufferSize = 500_000
        backpressure {
            strategy = BackpressureStrategy.ADAPTIVE
            maxPending = 100_000
            adaptiveThreshold = 0.8
        }
        monitoring {
            enableJmx = true
            metricsInterval = 1.seconds
        }
    }
    
    storage {
        inMemory {
            maxSize = 1_000_000
            evictionPolicy = EvictionPolicy.LRU
            concurrencyLevel = 16
        }
    }
}
```

## 🔌 Core Interfaces (Optimized for Performance)

### High-Performance Storage Interface

```kotlin
// Core storage interface - no framework dependencies
interface DLQStorage {
    // Single operations
    suspend fun save(record: DLQRecord): StorageResult
    suspend fun findById(id: String): DLQRecord?
    
    // Batch operations for performance
    suspend fun saveBatch(records: List<DLQRecord>): BatchStorageResult
    suspend fun findBatch(ids: List<String>): List<DLQRecord>
    
    // Streaming operations
    fun stream(criteria: QueryCriteria): Flow<DLQRecord>
    
    // Bulk operations
    suspend fun bulkUpdate(
        criteria: QueryCriteria,
        update: (DLQRecord) -> DLQRecord
    ): Int
}

// Storage results
sealed class StorageResult {
    data class Success(val id: String) : StorageResult()
    data class Failure(val error: String) : StorageResult()
}

data class BatchStorageResult(
    val successful: List<String>,
    val failed: List<Pair<Int, String>>, // index to error
    val duration: Duration
)
```

### High-Performance Processor

```kotlin
// Batch processor for high throughput
class BatchDLQProcessor(
    private val config: PerformanceConfig,
    private val storage: DLQStorage,
    private val interceptors: List<DLQInterceptor>
) {
    private val buffer = DLQRingBuffer(config.ringBufferSize)
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob()
    )
    
    init {
        // Start processing coroutines
        repeat(config.parallelism) {
            scope.launch {
                processBatches()
            }
        }
    }
    
    private suspend fun processBatches() {
        val batch = mutableListOf<DLQRecord>()
        
        while (true) {
            // Collect batch
            val deadline = System.nanoTime() + config.batchTimeout.toNanos()
            
            while (batch.size < config.batchSize && System.nanoTime() < deadline) {
                buffer.poll()?.let { batch.add(it) }
                    ?: delay(1) // Avoid busy waiting
            }
            
            if (batch.isNotEmpty()) {
                processBatch(batch)
                batch.clear()
            }
        }
    }
    
    private suspend fun processBatch(batch: List<DLQRecord>) {
        // Pre-process interceptors
        interceptors.forEach { interceptor ->
            batch.forEach { interceptor.beforeProcess(it) }
        }
        
        // Batch save for performance
        val result = storage.saveBatch(batch)
        
        // Post-process interceptors
        interceptors.forEach { interceptor ->
            batch.forEachIndexed { index, record ->
                if (index in result.successful) {
                    interceptor.afterProcess(record, true)
                } else {
                    interceptor.afterProcess(record, false)
                }
            }
        }
    }
}
```

### Backpressure Handler

```kotlin
// Adaptive backpressure management
class AdaptiveBackpressureHandler(
    private val config: BackpressureConfig
) {
    private val pendingCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)
    private val currentStrategy = AtomicReference(BackpressureStrategy.ADAPTIVE)
    
    fun shouldAccept(): Boolean {
        val pending = pendingCount.get()
        val strategy = currentStrategy.get()
        
        return when (strategy) {
            BackpressureStrategy.DROP_OLDEST -> {
                if (pending >= config.maxPending) {
                    dropOldest()
                    true
                } else true
            }
            BackpressureStrategy.DROP_NEWEST -> {
                pending < config.maxPending
            }
            BackpressureStrategy.BLOCK -> {
                while (pending >= config.maxPending) {
                    Thread.yield()
                }
                true
            }
            BackpressureStrategy.ADAPTIVE -> {
                adaptStrategy(pending)
                shouldAccept() // Recursive with new strategy
            }
        }
    }
    
    private fun adaptStrategy(pending: Long) {
        val utilization = pending.toDouble() / config.maxPending
        currentStrategy.set(
            when {
                utilization > 0.9 -> BackpressureStrategy.DROP_NEWEST
                utilization > 0.8 -> BackpressureStrategy.DROP_OLDEST
                else -> BackpressureStrategy.BLOCK
            }
        )
    }
}
```

## 📊 Performance Monitoring

### Metrics for High-Throughput Scenarios

```kotlin
object PerformanceMetrics {
    // Throughput metrics
    const val MESSAGES_PER_SECOND = "dlq.throughput.messages.per.second"
    const val BYTES_PER_SECOND = "dlq.throughput.bytes.per.second"
    const val BATCHES_PER_SECOND = "dlq.throughput.batches.per.second"
    
    // Latency metrics (in microseconds for precision)
    const val PROCESSING_LATENCY_MICROS = "dlq.latency.processing.micros"
    const val STORAGE_LATENCY_MICROS = "dlq.latency.storage.micros"
    const val TOTAL_LATENCY_MICROS = "dlq.latency.total.micros"
    
    // Buffer metrics
    const val BUFFER_UTILIZATION = "dlq.buffer.utilization.percentage"
    const val BUFFER_SIZE = "dlq.buffer.size"
    const val DROPPED_MESSAGES = "dlq.buffer.dropped.total"
    
    // Resource metrics
    const val HEAP_USAGE_MB = "dlq.resources.heap.mb"
    const val DIRECT_MEMORY_MB = "dlq.resources.direct.mb"
    const val GC_PRESSURE = "dlq.resources.gc.pressure"
    
    // Batch metrics
    const val BATCH_SIZE_AVG = "dlq.batch.size.average"
    const val BATCH_FILL_RATIO = "dlq.batch.fill.ratio"
}
```

## 🔄 Async Processing with Coroutines (Optimized)

### High-Performance Coroutine Processing

```kotlin
class HighPerformanceDLQProcessor(
    private val config: PerformanceConfig
) {
    // Use Channel with specific capacity for backpressure
    private val messageChannel = Channel<DLQRecord>(
        capacity = config.bufferSize,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    // Optimized dispatcher for CPU-bound work
    private val processingDispatcher = Dispatchers.IO.limitedParallelism(
        config.parallelism
    )
    
    suspend fun processMessages() = coroutineScope {
        // Create worker coroutines
        val workers = (1..config.parallelism).map { workerId ->
            launch(processingDispatcher) {
                processWorker(workerId)
            }
        }
        
        // Batch collector
        launch {
            messageChannel.consumeAsFlow()
                .buffer(config.bufferSize)
                .chunked(config.batchSize)
                .collect { batch ->
                    processBatch(batch)
                }
        }
    }
    
    private suspend fun processWorker(workerId: Int) {
        messageChannel.consumeEach { record ->
            processRecord(record)
        }
    }
}
```

## 🚀 Usage Examples

### High-Performance Configuration

```kotlin
// Example: Processing 100K+ messages per second
fun main() = runBlocking {
    val handler = dlqHandler {
        performance {
            batchSize = 5000
            parallelism = 32
            bufferSize = 1_000_000
            ringBufferSize = 131072 // 128K
            
            backpressure {
                strategy = BackpressureStrategy.ADAPTIVE
                maxPending = 500_000
                adaptiveThreshold = 0.85
            }
            
            memory {
                useDirectBuffers = true
                poolSize = 100.megabytes()
                recycleBatchSize = 100
            }
        }
        
        storage {
            // Use high-performance storage
            cassandra {
                contactPoints = listOf("node1", "node2", "node3")
                replicationFactor = 3
                consistencyLevel = ConsistencyLevel.ONE
                batchSize = 1000
            }
        }
        
        interceptors {
            +HighPerformanceMetricsInterceptor()
            +BatchLoggingInterceptor(logEveryN = 10000)
        }
    }
    
    // Start processing
    handler.start()
}
```

### Zero-Copy Message Processing

```kotlin
// Example: Zero-copy processing for maximum performance
class ZeroCopyDLQHandler {
    fun handleDirectBuffer(buffer: ByteBuffer): DLQRecord {
        // Read directly from ByteBuffer without copying
        val messageSize = buffer.getInt()
        val keySize = buffer.getInt()
        
        // Use direct ByteArray views
        val keyBytes = ByteArray(keySize)
        buffer.get(keyBytes)
        
        val payloadSize = messageSize - keySize - 8
        val payloadBytes = ByteArray(payloadSize)
        buffer.get(payloadBytes)
        
        return DLQRecord(
            messageKey = String(keyBytes),
            payload = payloadBytes, // No copy, direct reference
            // ... other fields
        )
    }
}
```

## 📈 Performance Benchmarks

### Target Performance Metrics

| Metric | Target | Conditions |
|--------|--------|------------|
| Throughput | 100,000+ msg/sec | Single instance, 8 cores |
| Latency (P50) | < 1ms | Including storage |
| Latency (P99) | < 10ms | Including storage |
| Memory/Message | < 1KB | Overhead per message |
| CPU Usage | < 50% | At 100K msg/sec |
| GC Pause | < 10ms | With G1GC |

### JMH Benchmark Example

```kotlin
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
class DLQBenchmark {
    
    private lateinit var processor: BatchDLQProcessor
    private lateinit var messages: List<DLQRecord>
    
    @Setup
    fun setup() {
        processor = BatchDLQProcessor(
            config = PerformanceConfig(
                batchSize = 1000,
                parallelism = 8
            ),
            storage = InMemoryDLQStorage(),
            interceptors = emptyList()
        )
        
        messages = (1..10000).map { 
            createTestDLQRecord() 
        }
    }
    
    @Benchmark
    fun measureThroughput(): Int {
        return runBlocking {
            processor.processBatch(messages)
            messages.size
        }
    }
}
```

---

*이 명세는 대규모 트래픽 처리를 위한 고성능 kafka-dlq-handler의 구현 기준입니다.*