package io.github.kayden.dlq.core.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName

class ProcessingMetadataTest {
    
    @Test
    @DisplayName("유효한 데이터로 ProcessingMetadata를 생성할 수 있다")
    fun shouldCreateProcessingMetadataWithValidData() {
        val metadata = ProcessingMetadata(
            processedBy = "processor-1",
            processingDurationMillis = 100L,
            batchId = "batch-123",
            attemptNumber = 2,
            nextRetryAt = System.currentTimeMillis() + 60000,
            backoffDelayMillis = 5000L,
            tags = mapOf("env" to "prod", "region" to "us-east-1"),
            metrics = ProcessingMetrics(messagesProcessed = 10)
        )
        
        assertEquals("processor-1", metadata.processedBy)
        assertEquals(100L, metadata.processingDurationMillis)
        assertEquals("batch-123", metadata.batchId)
        assertEquals(2, metadata.attemptNumber)
        assertEquals(5000L, metadata.backoffDelayMillis)
        assertEquals(2, metadata.tags.size)
        assertNotNull(metadata.metrics)
    }
    
    @Test
    @DisplayName("기본값으로 ProcessingMetadata를 생성할 수 있다")
    fun shouldCreateWithDefaults() {
        val metadata = ProcessingMetadata()
        
        assertNull(metadata.processedBy)
        assertNull(metadata.processingDurationMillis)
        assertNull(metadata.batchId)
        assertEquals(1, metadata.attemptNumber)
        assertNull(metadata.nextRetryAt)
        assertNull(metadata.backoffDelayMillis)
        assertTrue(metadata.tags.isEmpty())
        assertNull(metadata.metrics)
    }
    
    @Test
    @DisplayName("음수 시도 횟수로 생성 시 예외가 발생한다")
    fun shouldThrowExceptionWhenAttemptNumberIsNonPositive() {
        assertThrows<IllegalArgumentException> {
            ProcessingMetadata(attemptNumber = 0)
        }.also { exception ->
            assertEquals("Attempt number must be positive", exception.message)
        }
        
        assertThrows<IllegalArgumentException> {
            ProcessingMetadata(attemptNumber = -1)
        }
    }
    
    @Test
    @DisplayName("음수 처리 시간으로 생성 시 예외가 발생한다")
    fun shouldThrowExceptionWhenProcessingDurationIsNegative() {
        assertThrows<IllegalArgumentException> {
            ProcessingMetadata(processingDurationMillis = -1L)
        }.also { exception ->
            assertEquals("Processing duration must not be negative", exception.message)
        }
    }
    
    @Test
    @DisplayName("음수 백오프 지연으로 생성 시 예외가 발생한다")
    fun shouldThrowExceptionWhenBackoffDelayIsNegative() {
        assertThrows<IllegalArgumentException> {
            ProcessingMetadata(backoffDelayMillis = -1L)
        }.also { exception ->
            assertEquals("Backoff delay must not be negative", exception.message)
        }
    }
    
    @Test
    @DisplayName("단일 태그를 추가할 수 있다")
    fun shouldAddSingleTag() {
        val metadata = ProcessingMetadata()
        val updated = metadata.withTag("key", "value")
        
        assertEquals(1, updated.tags.size)
        assertEquals("value", updated.tags["key"])
        assertTrue(metadata.tags.isEmpty())
    }
    
    @Test
    @DisplayName("여러 태그를 추가할 수 있다")
    fun shouldAddMultipleTags() {
        val metadata = ProcessingMetadata(tags = mapOf("existing" to "value"))
        val newTags = mapOf("key1" to "value1", "key2" to "value2")
        val updated = metadata.withTags(newTags)
        
        assertEquals(3, updated.tags.size)
        assertEquals("value", updated.tags["existing"])
        assertEquals("value1", updated.tags["key1"])
        assertEquals("value2", updated.tags["key2"])
    }
    
    @Test
    @DisplayName("처리 시간을 올바르게 계산한다")
    fun shouldCalculateProcessingTime() {
        val metadata = ProcessingMetadata()
        val startTime = 1000L
        val endTime = 1500L
        
        val updated = metadata.withProcessingTime(startTime, endTime)
        
        assertEquals(500L, updated.processingDurationMillis)
    }
    
    @Test
    @DisplayName("종료 시간이 시작 시간보다 이전이면 예외가 발생한다")
    fun shouldThrowExceptionWhenEndTimeBeforeStartTime() {
        val metadata = ProcessingMetadata()
        
        assertThrows<IllegalArgumentException> {
            metadata.withProcessingTime(1500L, 1000L)
        }.also { exception ->
            assertEquals("End time must be after start time", exception.message)
        }
    }
    
    @Test
    @DisplayName("재시도 정보를 설정할 수 있다")
    fun shouldSetRetryInfo() {
        val metadata = ProcessingMetadata(attemptNumber = 2)
        val nextRetryTime = System.currentTimeMillis() + 60000
        val backoffDelay = 5000L
        
        val updated = metadata.withRetryInfo(nextRetryTime, backoffDelay)
        
        assertEquals(nextRetryTime, updated.nextRetryAt)
        assertEquals(backoffDelay, updated.backoffDelayMillis)
        assertEquals(3, updated.attemptNumber)
    }
    
    @Test
    @DisplayName("메트릭을 업데이트할 수 있다")
    fun shouldUpdateMetrics() {
        val metadata = ProcessingMetadata()
        val metrics = ProcessingMetrics(messagesProcessed = 100)
        
        val updated = metadata.withMetrics(metrics)
        
        assertEquals(metrics, updated.metrics)
    }
    
    @Test
    @DisplayName("처리 완료 상태를 올바르게 판단한다")
    fun shouldDetermineIfProcessed() {
        val unprocessed = ProcessingMetadata()
        assertFalse(unprocessed.isProcessed())
        
        val processed = ProcessingMetadata(processingDurationMillis = 100L)
        assertTrue(processed.isProcessed())
    }
    
    @Test
    @DisplayName("재시도 예정 상태를 올바르게 판단한다")
    fun shouldDetermineIfRetryScheduled() {
        val notScheduled = ProcessingMetadata()
        assertFalse(notScheduled.isRetryScheduled())
        
        val scheduled = ProcessingMetadata(nextRetryAt = System.currentTimeMillis() + 60000)
        assertTrue(scheduled.isRetryScheduled())
    }
    
    @Test
    @DisplayName("EMPTY 상수가 올바르게 정의되어 있다")
    fun shouldHaveEmptyConstant() {
        val empty = ProcessingMetadata.EMPTY
        
        assertNull(empty.processedBy)
        assertEquals(1, empty.attemptNumber)
        assertTrue(empty.tags.isEmpty())
        assertFalse(empty.isProcessed())
        assertFalse(empty.isRetryScheduled())
    }
    
    @Test
    @DisplayName("initial 팩토리 메서드가 올바르게 동작한다")
    fun shouldCreateInitialMetadata() {
        val metadata = ProcessingMetadata.initial("processor-1", "batch-123")
        
        assertEquals("processor-1", metadata.processedBy)
        assertEquals("batch-123", metadata.batchId)
        assertEquals(1, metadata.attemptNumber)
        
        val withoutBatch = ProcessingMetadata.initial("processor-2")
        assertEquals("processor-2", withoutBatch.processedBy)
        assertNull(withoutBatch.batchId)
    }
}

class ProcessingMetricsTest {
    
    @Test
    @DisplayName("유효한 데이터로 ProcessingMetrics를 생성할 수 있다")
    fun shouldCreateProcessingMetricsWithValidData() {
        val metrics = ProcessingMetrics(
            messagesProcessed = 100,
            messagesSuccess = 95,
            messagesFailed = 5,
            bytesProcessed = 10240,
            throughputMessagesPerSecond = 50.0,
            averageProcessingTimeMillis = 20.0
        )
        
        assertEquals(100, metrics.messagesProcessed)
        assertEquals(95, metrics.messagesSuccess)
        assertEquals(5, metrics.messagesFailed)
        assertEquals(10240, metrics.bytesProcessed)
        assertEquals(50.0, metrics.throughputMessagesPerSecond)
        assertEquals(20.0, metrics.averageProcessingTimeMillis)
    }
    
    @Test
    @DisplayName("음수 값으로 생성 시 예외가 발생한다")
    fun shouldThrowExceptionForNegativeValues() {
        assertThrows<IllegalArgumentException> {
            ProcessingMetrics(messagesProcessed = -1)
        }
        assertThrows<IllegalArgumentException> {
            ProcessingMetrics(messagesSuccess = -1)
        }
        assertThrows<IllegalArgumentException> {
            ProcessingMetrics(messagesFailed = -1)
        }
        assertThrows<IllegalArgumentException> {
            ProcessingMetrics(bytesProcessed = -1)
        }
        assertThrows<IllegalArgumentException> {
            ProcessingMetrics(throughputMessagesPerSecond = -1.0)
        }
        assertThrows<IllegalArgumentException> {
            ProcessingMetrics(averageProcessingTimeMillis = -1.0)
        }
    }
    
    @Test
    @DisplayName("성공+실패 메시지가 전체를 초과하면 예외가 발생한다")
    fun shouldThrowExceptionWhenSuccessAndFailedExceedTotal() {
        assertThrows<IllegalArgumentException> {
            ProcessingMetrics(
                messagesProcessed = 100,
                messagesSuccess = 60,
                messagesFailed = 50
            )
        }.also { exception ->
            assertEquals("Success + Failed messages cannot exceed total processed", exception.message)
        }
    }
    
    @Test
    @DisplayName("성공률을 올바르게 계산한다")
    fun shouldCalculateSuccessRate() {
        val metrics = ProcessingMetrics(
            messagesProcessed = 100,
            messagesSuccess = 95,
            messagesFailed = 5
        )
        assertEquals(0.95, metrics.successRate())
        
        val noMessages = ProcessingMetrics()
        assertNull(noMessages.successRate())
    }
    
    @Test
    @DisplayName("실패율을 올바르게 계산한다")
    fun shouldCalculateFailureRate() {
        val metrics = ProcessingMetrics(
            messagesProcessed = 100,
            messagesSuccess = 95,
            messagesFailed = 5
        )
        assertEquals(0.05, metrics.failureRate())
        
        val noMessages = ProcessingMetrics()
        assertNull(noMessages.failureRate())
    }
    
    @Test
    @DisplayName("메시지당 평균 바이트를 올바르게 계산한다")
    fun shouldCalculateAverageBytesPerMessage() {
        val metrics = ProcessingMetrics(
            messagesProcessed = 100,
            bytesProcessed = 10240
        )
        assertEquals(102.4, metrics.averageBytesPerMessage())
        
        val noMessages = ProcessingMetrics()
        assertNull(noMessages.averageBytesPerMessage())
    }
    
    @Test
    @DisplayName("여러 메트릭을 올바르게 병합한다")
    fun shouldMergeMetrics() {
        val metrics1 = ProcessingMetrics(
            messagesProcessed = 100,
            messagesSuccess = 90,
            messagesFailed = 10,
            bytesProcessed = 1000,
            throughputMessagesPerSecond = 50.0,
            averageProcessingTimeMillis = 20.0
        )
        
        val metrics2 = ProcessingMetrics(
            messagesProcessed = 200,
            messagesSuccess = 180,
            messagesFailed = 20,
            bytesProcessed = 2000,
            throughputMessagesPerSecond = 60.0,
            averageProcessingTimeMillis = 25.0
        )
        
        val merged = ProcessingMetrics.merge(metrics1, metrics2)
        
        assertEquals(300, merged.messagesProcessed)
        assertEquals(270, merged.messagesSuccess)
        assertEquals(30, merged.messagesFailed)
        assertEquals(3000, merged.bytesProcessed)
        assertEquals(55.0, merged.throughputMessagesPerSecond)
        assertEquals(22.5, merged.averageProcessingTimeMillis)
    }
    
    @Test
    @DisplayName("빈 메트릭 배열을 병합하면 EMPTY를 반환한다")
    fun shouldReturnEmptyWhenMergingNoMetrics() {
        val merged = ProcessingMetrics.merge()
        assertEquals(ProcessingMetrics.EMPTY, merged)
    }
    
    @Test
    @DisplayName("null 평균값을 가진 메트릭을 병합할 수 있다")
    fun shouldMergeMetricsWithNullAverages() {
        val metrics1 = ProcessingMetrics(messagesProcessed = 100)
        val metrics2 = ProcessingMetrics(
            messagesProcessed = 200,
            throughputMessagesPerSecond = 60.0
        )
        
        val merged = ProcessingMetrics.merge(metrics1, metrics2)
        
        assertEquals(300, merged.messagesProcessed)
        assertEquals(60.0, merged.throughputMessagesPerSecond)
        assertNull(merged.averageProcessingTimeMillis)
    }
}