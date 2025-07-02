package io.github.kayden.dlq.core.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

@DisplayName("DLQRecord 테스트")
class DLQRecordTest {
    
    @Nested
    @DisplayName("생성 및 검증")
    inner class CreationAndValidation {
        
        @Test
        fun `유효한 데이터로 DLQRecord 생성 성공`() {
            val record = createValidDLQRecord()
            
            assertThat(record.id).isNotBlank()
            assertThat(record.originalTopic).isEqualTo("test-topic")
            assertThat(record.originalPartition).isEqualTo(0)
            assertThat(record.originalOffset).isEqualTo(100L)
            assertThat(record.status).isEqualTo(DLQStatus.PENDING)
            assertThat(record.retryCount).isEqualTo(0)
        }
        
        @Test
        fun `빈 원본 토픽으로 생성 시 예외 발생`() {
            assertThatThrownBy {
                createValidDLQRecord(originalTopic = "")
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Original topic must not be blank")
        }
        
        @Test
        fun `음수 파티션으로 생성 시 예외 발생`() {
            assertThatThrownBy {
                createValidDLQRecord(originalPartition = -1)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Original partition must not be negative")
        }
        
        @Test
        fun `음수 오프셋으로 생성 시 예외 발생`() {
            assertThatThrownBy {
                createValidDLQRecord(originalOffset = -1L)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Original offset must not be negative")
        }
        
        @Test
        fun `음수 재시도 횟수로 생성 시 예외 발생`() {
            assertThatThrownBy {
                createValidDLQRecord(retryCount = -1)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Retry count must not be negative")
        }
        
        @Test
        fun `updatedAt이 createdAt보다 이전일 때 예외 발생`() {
            val createdAt = System.currentTimeMillis()
            val updatedAt = createdAt - 1000
            
            assertThatThrownBy {
                createValidDLQRecord(createdAt = createdAt, updatedAt = updatedAt)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Updated timestamp must not be before created timestamp")
        }
    }
    
    @Nested
    @DisplayName("equals와 hashCode")
    inner class EqualsAndHashCode {
        
        @Test
        fun `동일한 ID를 가진 레코드는 동일하다고 판단`() {
            val id = "test-id"
            val record1 = createValidDLQRecord(id = id, retryCount = 1)
            val record2 = createValidDLQRecord(id = id, retryCount = 2)
            
            assertThat(record1).isEqualTo(record2)
            assertThat(record1.hashCode()).isEqualTo(record2.hashCode())
        }
        
        @Test
        fun `다른 ID를 가진 레코드는 다르다고 판단`() {
            val record1 = createValidDLQRecord(id = "id1")
            val record2 = createValidDLQRecord(id = "id2")
            
            assertThat(record1).isNotEqualTo(record2)
        }
        
        @Test
        fun `ByteArray 페이로드가 달라도 ID가 같으면 동일하다고 판단`() {
            val id = "test-id"
            val record1 = createValidDLQRecord(id = id, payload = "payload1".toByteArray())
            val record2 = createValidDLQRecord(id = id, payload = "payload2".toByteArray())
            
            assertThat(record1).isEqualTo(record2)
        }
    }
    
    @Nested
    @DisplayName("Lazy 속성")
    inner class LazyProperties {
        
        @Test
        fun `payloadAsString은 ByteArray를 올바르게 변환`() {
            val payload = "테스트 페이로드"
            val record = createValidDLQRecord(payload = payload.toByteArray())
            
            assertThat(record.payloadAsString).isEqualTo(payload)
        }
        
        @Test
        fun `headersAsStringMap은 ByteArray 맵을 올바르게 변환`() {
            val headers = mapOf(
                "header1" to "value1".toByteArray(),
                "header2" to "value2".toByteArray()
            )
            val record = createValidDLQRecord(headers = headers)
            
            assertThat(record.headersAsStringMap).containsExactlyInAnyOrderEntriesOf(
                mapOf("header1" to "value1", "header2" to "value2")
            )
        }
    }
    
    @Nested
    @DisplayName("비즈니스 메서드")
    inner class BusinessMethods {
        
        @Test
        fun `PENDING 상태이고 재시도 횟수가 한계 미만이면 재시도 가능`() {
            val record = createValidDLQRecord(
                status = DLQStatus.PENDING,
                retryCount = 2
            )
            
            assertThat(record.canRetry(maxRetries = 3)).isTrue()
        }
        
        @Test
        fun `PENDING 상태가 아니면 재시도 불가능`() {
            val record = createValidDLQRecord(
                status = DLQStatus.SUCCESS,
                retryCount = 0
            )
            
            assertThat(record.canRetry()).isFalse()
        }
        
        @Test
        fun `재시도 횟수가 한계에 도달하면 재시도 불가능`() {
            val record = createValidDLQRecord(
                status = DLQStatus.PENDING,
                retryCount = 3
            )
            
            assertThat(record.canRetry(maxRetries = 3)).isFalse()
        }
        
        @Test
        fun `withRetry는 상태와 카운트를 올바르게 업데이트`() {
            val original = createValidDLQRecord(
                status = DLQStatus.PENDING,
                retryCount = 1
            )
            val beforeUpdate = original.updatedAt
            
            Thread.sleep(10) // updatedAt이 변경되도록 대기
            val retried = original.withRetry()
            
            assertThat(retried.status).isEqualTo(DLQStatus.RETRYING)
            assertThat(retried.retryCount).isEqualTo(2)
            assertThat(retried.lastRetryAt).isNotNull()
            assertThat(retried.updatedAt).isGreaterThan(beforeUpdate)
        }
        
        @Test
        fun `withStatus는 상태를 올바르게 업데이트`() {
            val original = createValidDLQRecord(status = DLQStatus.PENDING)
            val beforeUpdate = original.updatedAt
            
            Thread.sleep(10)
            val updated = original.withStatus(DLQStatus.SUCCESS)
            
            assertThat(updated.status).isEqualTo(DLQStatus.SUCCESS)
            assertThat(updated.updatedAt).isGreaterThan(beforeUpdate)
        }
        
        @Test
        fun `withMetadata는 메타데이터를 올바르게 업데이트`() {
            val original = createValidDLQRecord(processingMetadata = null)
            val metadata = ProcessingMetadata(
                tags = setOf("test-tag"),
                customAttributes = mapOf("key" to "value")
            )
            val beforeUpdate = original.updatedAt
            
            Thread.sleep(10)
            val updated = original.withMetadata(metadata)
            
            assertThat(updated.processingMetadata).isEqualTo(metadata)
            assertThat(updated.updatedAt).isGreaterThan(beforeUpdate)
        }
    }
    
    private fun createValidDLQRecord(
        id: String = "test-id",
        messageKey: String? = "test-key",
        originalTopic: String = "test-topic",
        originalPartition: Int = 0,
        originalOffset: Long = 100L,
        payload: ByteArray = "test-payload".toByteArray(),
        headers: Map<String, ByteArray> = mapOf("header" to "value".toByteArray()),
        errorClass: String? = "TestException",
        errorMessage: String? = "Test error",
        errorType: ErrorType = ErrorType.TRANSIENT,
        stackTrace: String? = "stack trace",
        status: DLQStatus = DLQStatus.PENDING,
        retryCount: Int = 0,
        lastRetryAt: Long? = null,
        processingMetadata: ProcessingMetadata? = null,
        createdAt: Long = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis()
    ): DLQRecord = DLQRecord(
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
        processingMetadata = processingMetadata,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}