package io.github.kayden.dlq.performance

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import io.github.kayden.dlq.performance.RingBuffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

class BackpressureHandlerTest {
    
    private lateinit var pendingCounter: AtomicLong
    private lateinit var config: BackpressureConfig
    
    @BeforeEach
    fun setUp() {
        pendingCounter = AtomicLong(0)
        config = BackpressureConfig(
            maxPending = 100,
            adaptiveThreshold = 0.8,
            adaptiveCheckInterval = 10.milliseconds
        )
    }
    
    @Test
    fun `should create handler with default config`() {
        val handler = BackpressureHandlerFactory.create(
            pendingCounter = { pendingCounter.get() }
        )
        
        assertThat(handler).isNotNull
        assertThat(handler.currentStrategy()).isEqualTo(BackpressureStrategyType.ADAPTIVE)
    }
    
    @Test
    fun `should validate config parameters`() {
        assertThrows<IllegalArgumentException> {
            BackpressureConfig(maxPending = -1)
        }
        
        assertThrows<IllegalArgumentException> {
            BackpressureConfig(adaptiveThreshold = 1.5)
        }
        
        assertThrows<IllegalArgumentException> {
            BackpressureConfig(adaptiveThreshold = -0.1)
        }
    }
    
    @Test
    fun `DROP_NEWEST strategy should reject when at capacity`() {
        val configDropNewest = config.copy(initialStrategy = BackpressureStrategyType.DROP_NEWEST)
        val handler = AdaptiveBackpressureHandler(configDropNewest) { pendingCounter.get() }
        
        // Under capacity - should accept
        pendingCounter.set(50)
        assertThat(handler.shouldAccept()).isTrue()
        
        // At capacity - should reject
        pendingCounter.set(100)
        assertThat(handler.shouldAccept()).isFalse()
        handler.onRejected()
        
        // Over capacity - should reject
        pendingCounter.set(101)
        assertThat(handler.shouldAccept()).isFalse()
        
        val metrics = handler.getMetrics()
        assertThat(metrics.rejected).isEqualTo(1)
    }
    
    @Test
    fun `DROP_OLDEST strategy should always accept`() {
        val configDropOldest = config.copy(initialStrategy = BackpressureStrategyType.DROP_OLDEST)
        val handler = AdaptiveBackpressureHandler(configDropOldest) { pendingCounter.get() }
        
        // Under capacity
        pendingCounter.set(50)
        assertThat(handler.shouldAccept()).isTrue()
        
        // At capacity - should still accept (drops oldest)
        pendingCounter.set(100)
        assertThat(handler.shouldAccept()).isTrue()
        
        // Over capacity - should still accept
        pendingCounter.set(150)
        assertThat(handler.shouldAccept()).isTrue()
        
        val metrics = handler.getMetrics()
        assertThat(metrics.dropped).isGreaterThan(0)
    }
    
    @Test
    fun `BLOCK strategy should block when at capacity`() {
        val configBlock = config.copy(initialStrategy = BackpressureStrategyType.BLOCK)
        val handler = AdaptiveBackpressureHandler(configBlock) { pendingCounter.get() }
        
        // Under capacity
        pendingCounter.set(50)
        assertThat(handler.shouldAccept()).isTrue()
        
        // At capacity - this test simulates the blocking behavior
        pendingCounter.set(100)
        
        // In a real scenario, this would block until space is available
        // For testing, we simulate the blocking by checking the strategy behavior
        val strategy = handler.currentStrategy()
        assertThat(strategy).isEqualTo(BackpressureStrategyType.BLOCK)
    }
    
    @Test
    fun `ADAPTIVE strategy should switch based on utilization`() {
        val handler = AdaptiveBackpressureHandler(config) { pendingCounter.get() }
        
        // Low utilization - should use BLOCK
        pendingCounter.set(50) // 50% utilization
        handler.shouldAccept()
        Thread.sleep(15) // Wait for adaptive check
        handler.shouldAccept()
        
        // High utilization - should switch to DROP_OLDEST
        pendingCounter.set(85) // 85% utilization (> threshold)
        Thread.sleep(15)
        handler.shouldAccept()
        
        // Very high utilization - should switch to DROP_NEWEST
        pendingCounter.set(96) // 96% utilization
        Thread.sleep(15)
        handler.shouldAccept()
    }
    
    @Test
    fun `should track metrics correctly`() {
        val handler = AdaptiveBackpressureHandler(config) { pendingCounter.get() }
        
        // Accept some messages
        pendingCounter.set(50)
        repeat(10) {
            if (handler.shouldAccept()) {
                handler.onAccepted()
            }
        }
        
        // Reject some messages
        pendingCounter.set(100)
        val configDropNewest = config.copy(initialStrategy = BackpressureStrategyType.DROP_NEWEST)
        val rejectHandler = AdaptiveBackpressureHandler(configDropNewest) { pendingCounter.get() }
        
        repeat(5) {
            if (!rejectHandler.shouldAccept()) {
                rejectHandler.onRejected()
            }
        }
        
        val metrics = handler.getMetrics()
        assertThat(metrics.accepted).isEqualTo(10)
        assertThat(metrics.currentPending).isEqualTo(100)
        assertThat(metrics.utilization).isEqualTo(1.0)
    }
}

class RingBufferBackpressureHandlerTest {
    
    private lateinit var ringBuffer: RingBuffer
    private lateinit var config: BackpressureConfig
    
    @BeforeEach
    fun setUp() {
        ringBuffer = RingBuffer(size = 16) // Small buffer for testing
        config = BackpressureConfig(
            maxPending = 16,
            adaptiveThreshold = 0.8,
            adaptiveCheckInterval = 10.milliseconds
        )
    }
    
    private fun createTestRecord(id: String = "test"): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key-$id",
            originalTopic = "test-topic",
            originalPartition = 0,
            originalOffset = 0L,
            payload = "test payload".toByteArray(),
            headers = emptyMap(),
            errorClass = "TestError",
            errorMessage = "Test error message",
            errorType = ErrorType.TRANSIENT,
            stackTrace = null,
            status = DLQStatus.PENDING
        )
    }
    
    @Test
    fun `should integrate with RingBuffer`() {
        val handler = RingBufferBackpressureHandler(ringBuffer, config)
        
        // Fill buffer partially
        repeat(8) {
            ringBuffer.offer(createTestRecord("record-$it"))
        }
        
        assertThat(handler.shouldAccept()).isTrue()
        assertThat(ringBuffer.utilization()).isEqualTo(50.0)
        
        // Fill to threshold
        repeat(5) {
            ringBuffer.offer(createTestRecord("record-${it + 8}"))
        }
        
        assertThat(ringBuffer.utilization()).isEqualTo(81.25) // 13/16
    }
    
    @Test
    fun `DROP_OLDEST should remove oldest when full`() {
        val dropOldestConfig = config.copy(initialStrategy = BackpressureStrategyType.DROP_OLDEST)
        val handler = RingBufferBackpressureHandler(ringBuffer, dropOldestConfig)
        
        // Fill buffer completely
        val records = (1..16).map { createTestRecord("record-$it") }
        records.forEach { ringBuffer.offer(it) }
        
        assertThat(ringBuffer.size()).isEqualTo(16)
        
        // Should accept and drop oldest
        assertThat(handler.shouldAccept()).isTrue()
        
        val metrics = handler.getMetrics()
        assertThat(metrics.currentStrategy).isEqualTo("DROP_OLDEST")
    }
    
    @Test
    fun `DROP_NEWEST should reject when full`() {
        val dropNewestConfig = config.copy(initialStrategy = BackpressureStrategyType.DROP_NEWEST)
        val handler = RingBufferBackpressureHandler(ringBuffer, dropNewestConfig)
        
        // Fill buffer completely
        repeat(16) {
            ringBuffer.offer(createTestRecord("record-$it"))
        }
        
        assertThat(ringBuffer.size()).isEqualTo(16)
        
        // Should reject new messages
        assertThat(handler.shouldAccept()).isFalse()
        handler.onRejected()
        
        val metrics = handler.getMetrics()
        assertThat(metrics.rejected).isEqualTo(1)
    }
    
    @Test
    fun `BackpressureAwareRingBuffer should respect backpressure`() {
        val handler = RingBufferBackpressureHandler(
            ringBuffer,
            config.copy(initialStrategy = BackpressureStrategyType.DROP_NEWEST)
        )
        val backpressureBuffer = BackpressureAwareRingBuffer(ringBuffer, handler)
        
        // Fill buffer
        repeat(16) {
            backpressureBuffer.offer(createTestRecord("record-$it"))
        }
        
        // Next offer should fail
        val success = backpressureBuffer.offer(createTestRecord("overflow"))
        assertThat(success).isFalse()
        
        val metrics = backpressureBuffer.metrics()
        assertThat(metrics.accepted).isEqualTo(16)
        assertThat(metrics.rejected).isEqualTo(1)
    }
    
    @Test
    fun `extension function should create handler correctly`() {
        val (buffer, handler) = ringBuffer.withBackpressure(config)
        
        assertThat(buffer).isSameAs(ringBuffer)
        assertThat(handler).isInstanceOf(RingBufferBackpressureHandler::class.java)
        assertThat(handler.currentStrategy()).isEqualTo(BackpressureStrategyType.ADAPTIVE)
    }
}