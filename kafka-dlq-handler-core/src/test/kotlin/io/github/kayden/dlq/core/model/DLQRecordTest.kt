package io.github.kayden.dlq.core.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import java.util.UUID

class DLQRecordTest {
    
    @Test
    @DisplayName("유효한 데이터로 DLQRecord를 생성할 수 있다")
    fun shouldCreateDLQRecordWithValidData() {
        val record = createTestDLQRecord()
        
        assertNotNull(record.id)
        assertEquals("test-key", record.messageKey)
        assertEquals("test-topic", record.originalTopic)
        assertEquals(0, record.originalPartition)
        assertEquals(123L, record.originalOffset)
        assertArrayEquals("test payload".toByteArray(), record.payload)
        assertEquals(DLQStatus.PENDING, record.status)
        assertEquals(0, record.retryCount)
        assertEquals(ErrorType.TRANSIENT_NETWORK_ERROR, record.errorType)
    }
    
    @Test
    @DisplayName("빈 토픽 이름으로 생성 시 예외가 발생한다")
    fun shouldThrowExceptionWhenTopicIsBlank() {
        assertThrows<IllegalArgumentException> {
            createTestDLQRecord(originalTopic = "")
        }.also { exception ->
            assertEquals("Original topic must not be blank", exception.message)
        }
    }
    
    @Test
    @DisplayName("음수 파티션으로 생성 시 예외가 발생한다")
    fun shouldThrowExceptionWhenPartitionIsNegative() {
        assertThrows<IllegalArgumentException> {
            createTestDLQRecord(originalPartition = -1)
        }.also { exception ->
            assertEquals("Original partition must not be negative", exception.message)
        }
    }
    
    @Test
    @DisplayName("음수 오프셋으로 생성 시 예외가 발생한다")
    fun shouldThrowExceptionWhenOffsetIsNegative() {
        assertThrows<IllegalArgumentException> {
            createTestDLQRecord(originalOffset = -1)
        }.also { exception ->
            assertEquals("Original offset must not be negative", exception.message)
        }
    }
    
    @Test
    @DisplayName("음수 재시도 횟수로 생성 시 예외가 발생한다")
    fun shouldThrowExceptionWhenRetryCountIsNegative() {
        assertThrows<IllegalArgumentException> {
            createTestDLQRecord(retryCount = -1)
        }.also { exception ->
            assertEquals("Retry count must not be negative", exception.message)
        }
    }
    
    @Test
    @DisplayName("빈 페이로드로 생성 시 예외가 발생한다")
    fun shouldThrowExceptionWhenPayloadIsEmpty() {
        assertThrows<IllegalArgumentException> {
            createTestDLQRecord(payload = ByteArray(0))
        }.also { exception ->
            assertEquals("Payload must not be empty", exception.message)
        }
    }
    
    @Test
    @DisplayName("payloadAsString은 지연 로딩되며 올바르게 변환된다")
    fun shouldLazilyConvertPayloadToString() {
        val payload = "한글 테스트 메시지".toByteArray()
        val record = createTestDLQRecord(payload = payload)
        
        assertEquals("한글 테스트 메시지", record.payloadAsString)
    }
    
    @Test
    @DisplayName("headersAsStringMap은 지연 로딩되며 올바르게 변환된다")
    fun shouldLazilyConvertHeadersToStringMap() {
        val headers = mapOf(
            "header1" to "value1".toByteArray(),
            "header2" to "value2".toByteArray()
        )
        val record = createTestDLQRecord(headers = headers)
        
        val stringHeaders = record.headersAsStringMap
        assertEquals(2, stringHeaders.size)
        assertEquals("value1", stringHeaders["header1"])
        assertEquals("value2", stringHeaders["header2"])
    }
    
    @Test
    @DisplayName("canRetry는 재시도 가능한 상태를 올바르게 판단한다")
    fun shouldDetermineRetryEligibility() {
        val pendingRecord = createTestDLQRecord(
            status = DLQStatus.PENDING,
            retryCount = 2,
            errorType = ErrorType.TRANSIENT_NETWORK_ERROR
        )
        assertTrue(pendingRecord.canRetry(3))
        assertFalse(pendingRecord.canRetry(2))
        
        val successRecord = createTestDLQRecord(
            status = DLQStatus.SUCCESS,
            retryCount = 0
        )
        assertFalse(successRecord.canRetry())
        
        val permanentErrorRecord = createTestDLQRecord(
            status = DLQStatus.PENDING,
            retryCount = 0,
            errorType = ErrorType.PERMANENT_FAILURE
        )
        assertFalse(permanentErrorRecord.canRetry())
    }
    
    @Test
    @DisplayName("withRetry는 재시도 상태로 올바르게 업데이트한다")
    fun shouldUpdateForRetry() {
        val original = createTestDLQRecord(retryCount = 1)
        val beforeUpdate = original.updatedAt
        
        Thread.sleep(10)
        val updated = original.withRetry()
        
        assertEquals(DLQStatus.RETRYING, updated.status)
        assertEquals(2, updated.retryCount)
        assertNotNull(updated.lastRetryAt)
        assertTrue(updated.updatedAt > beforeUpdate)
        assertEquals(original.id, updated.id)
    }
    
    @Test
    @DisplayName("withStatus는 상태를 올바르게 업데이트한다")
    fun shouldUpdateStatus() {
        val original = createTestDLQRecord(status = DLQStatus.PENDING)
        val updated = original.withStatus(DLQStatus.SUCCESS)
        
        assertEquals(DLQStatus.SUCCESS, updated.status)
        assertEquals(original.id, updated.id)
        assertEquals(original.retryCount, updated.retryCount)
    }
    
    @Test
    @DisplayName("withProcessingMetadata는 메타데이터를 올바르게 업데이트한다")
    fun shouldUpdateProcessingMetadata() {
        val original = createTestDLQRecord()
        val metadata = ProcessingMetadata(
            processedBy = "test-processor",
            processingDurationMillis = 100
        )
        val updated = original.withProcessingMetadata(metadata)
        
        assertEquals(metadata, updated.processingMetadata)
        assertEquals(original.id, updated.id)
    }
    
    @Test
    @DisplayName("equals와 hashCode는 ID 기반으로 동작한다")
    fun shouldImplementEqualsAndHashCodeBasedOnId() {
        val id = UUID.randomUUID().toString()
        val record1 = createTestDLQRecord(id = id, messageKey = "key1")
        val record2 = createTestDLQRecord(id = id, messageKey = "key2")
        val record3 = createTestDLQRecord(messageKey = "key1")
        
        assertEquals(record1, record2)
        assertNotEquals(record1, record3)
        assertEquals(record1.hashCode(), record2.hashCode())
        assertNotEquals(record1.hashCode(), record3.hashCode())
    }
    
    @Test
    @DisplayName("toString은 주요 정보만 포함한다")
    fun shouldProvideReadableToString() {
        val record = createTestDLQRecord(
            id = "test-id",
            originalTopic = "test-topic",
            status = DLQStatus.PENDING,
            retryCount = 2,
            errorType = ErrorType.TRANSIENT_NETWORK_ERROR
        )
        
        val string = record.toString()
        assertTrue(string.contains("test-id"))
        assertTrue(string.contains("test-topic"))
        assertTrue(string.contains("PENDING"))
        assertTrue(string.contains("2"))
        assertTrue(string.contains("TRANSIENT_NETWORK_ERROR"))
    }
    
    private fun createTestDLQRecord(
        id: String = UUID.randomUUID().toString(),
        messageKey: String? = "test-key",
        originalTopic: String = "test-topic",
        originalPartition: Int = 0,
        originalOffset: Long = 123L,
        payload: ByteArray = "test payload".toByteArray(),
        headers: Map<String, ByteArray> = mapOf("test-header" to "test-value".toByteArray()),
        errorClass: String? = "java.io.IOException",
        errorMessage: String? = "Test error",
        errorType: ErrorType = ErrorType.TRANSIENT_NETWORK_ERROR,
        stackTrace: String? = null,
        status: DLQStatus = DLQStatus.PENDING,
        retryCount: Int = 0,
        lastRetryAt: Long? = null,
        processingMetadata: ProcessingMetadata? = null
    ): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = messageKey,
            originalTopic = originalTopic,
            originalPartition = originalPartition,
            originalOffset = originalOffset,
            payload = payload,
            headers = headers,
            errorClass = errorClass,
            errorMessage = errorMessage,
            errorType = errorType,
            stackTrace = stackTrace,
            status = status,
            retryCount = retryCount,
            lastRetryAt = lastRetryAt,
            processingMetadata = processingMetadata
        )
    }
}