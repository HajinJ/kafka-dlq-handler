package io.github.kayden.dlq.storage

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@DisplayName("StorageResult 테스트")
class StorageResultTest {
    
    @Test
    @DisplayName("Success 생성 및 속성 확인")
    fun createSuccess() {
        val id = "test-id"
        val timestamp = Instant.now()
        val result = StorageResult.Success(id, timestamp)
        
        assertThat(result.id).isEqualTo(id)
        assertThat(result.timestamp).isEqualTo(timestamp)
        assertThat(result.isSuccess()).isTrue
        assertThat(result.isFailure()).isFalse
        assertThat(result.getIdOrNull()).isEqualTo(id)
        assertThat(result.getIdOrThrow()).isEqualTo(id)
    }
    
    @Test
    @DisplayName("Success 기본 timestamp 사용")
    fun createSuccessWithDefaultTimestamp() {
        val before = Instant.now()
        val result = StorageResult.Success("test-id")
        val after = Instant.now()
        
        assertThat(result.timestamp).isBetween(before, after)
    }
    
    @Test
    @DisplayName("Failure 생성 및 속성 확인")
    fun createFailure() {
        val error = "Storage error"
        val cause = RuntimeException("DB connection failed")
        val timestamp = Instant.now()
        val result = StorageResult.Failure(error, cause, timestamp)
        
        assertThat(result.error).isEqualTo(error)
        assertThat(result.cause).isEqualTo(cause)
        assertThat(result.timestamp).isEqualTo(timestamp)
        assertThat(result.isSuccess()).isFalse
        assertThat(result.isFailure()).isTrue
        assertThat(result.getIdOrNull()).isNull()
    }
    
    @Test
    @DisplayName("Failure에서 getIdOrThrow 호출 시 예외 발생")
    fun failureGetIdOrThrowThrowsException() {
        val error = "Storage failed"
        val cause = RuntimeException("DB error")
        val result = StorageResult.Failure(error, cause)
        
        assertThatThrownBy { result.getIdOrThrow() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Storage operation failed: $error")
            .hasCause(cause)
    }
    
    @Test
    @DisplayName("BatchStorageResult 생성 및 계산된 속성 확인")
    fun createBatchStorageResult() {
        val successful = listOf("id1", "id2", "id3")
        val failed = listOf(
            BatchStorageResult.FailedRecord(4, "Validation error"),
            BatchStorageResult.FailedRecord(6, "Duplicate key")
        )
        val duration = Duration.ofMillis(500)
        
        val result = BatchStorageResult(successful, failed, duration)
        
        assertThat(result.successful).containsExactly("id1", "id2", "id3")
        assertThat(result.failed).hasSize(2)
        assertThat(result.duration).isEqualTo(duration)
        assertThat(result.totalCount).isEqualTo(5)
        assertThat(result.successRate).isEqualTo(0.6) // 3/5
        assertThat(result.throughput).isEqualTo(10.0) // 5 records / 0.5 seconds
    }
    
    @Test
    @DisplayName("BatchStorageResult 완전 성공 확인")
    fun batchStorageResultFullSuccess() {
        val result = BatchStorageResult(
            successful = listOf("id1", "id2", "id3"),
            failed = emptyList(),
            duration = Duration.ofMillis(100)
        )
        
        assertThat(result.isFullSuccess()).isTrue
        assertThat(result.isPartialSuccess()).isFalse
        assertThat(result.isFullFailure()).isFalse
        assertThat(result.successRate).isEqualTo(1.0)
    }
    
    @Test
    @DisplayName("BatchStorageResult 부분 성공 확인")
    fun batchStorageResultPartialSuccess() {
        val result = BatchStorageResult(
            successful = listOf("id1", "id2"),
            failed = listOf(BatchStorageResult.FailedRecord(2, "Error")),
            duration = Duration.ofMillis(100)
        )
        
        assertThat(result.isFullSuccess()).isFalse
        assertThat(result.isPartialSuccess()).isTrue
        assertThat(result.isFullFailure()).isFalse
        assertThat(result.successRate).isEqualTo(2.0 / 3.0)
    }
    
    @Test
    @DisplayName("BatchStorageResult 완전 실패 확인")
    fun batchStorageResultFullFailure() {
        val result = BatchStorageResult(
            successful = emptyList(),
            failed = listOf(
                BatchStorageResult.FailedRecord(0, "Error 1"),
                BatchStorageResult.FailedRecord(1, "Error 2")
            ),
            duration = Duration.ofMillis(100)
        )
        
        assertThat(result.isFullSuccess()).isFalse
        assertThat(result.isPartialSuccess()).isFalse
        assertThat(result.isFullFailure()).isTrue
        assertThat(result.successRate).isEqualTo(0.0)
    }
    
    @Test
    @DisplayName("BatchStorageResult 빈 결과 처리")
    fun batchStorageResultEmpty() {
        val result = BatchStorageResult(
            successful = emptyList(),
            failed = emptyList(),
            duration = Duration.ofMillis(10)
        )
        
        assertThat(result.totalCount).isEqualTo(0)
        assertThat(result.successRate).isEqualTo(0.0)
        assertThat(result.throughput).isEqualTo(0.0)
        assertThat(result.isFullSuccess()).isFalse
        assertThat(result.isPartialSuccess()).isFalse
        assertThat(result.isFullFailure()).isFalse
    }
    
    @Test
    @DisplayName("BatchStorageResult zero duration 처리")
    fun batchStorageResultZeroDuration() {
        val result = BatchStorageResult(
            successful = listOf("id1"),
            failed = emptyList(),
            duration = Duration.ZERO
        )
        
        assertThat(result.throughput).isEqualTo(0.0)
    }
    
    @Test
    @DisplayName("FailedRecord 생성 및 속성 확인")
    fun createFailedRecord() {
        val cause = RuntimeException("Validation failed")
        val failedRecord = BatchStorageResult.FailedRecord(
            index = 5,
            error = "Invalid data",
            cause = cause
        )
        
        assertThat(failedRecord.index).isEqualTo(5)
        assertThat(failedRecord.error).isEqualTo("Invalid data")
        assertThat(failedRecord.cause).isEqualTo(cause)
    }
}