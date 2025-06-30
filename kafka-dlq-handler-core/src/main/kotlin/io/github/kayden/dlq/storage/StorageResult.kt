package io.github.kayden.dlq.storage

import java.time.Duration
import java.time.Instant

/**
 * Storage 작업의 결과를 나타내는 sealed class
 * 
 * 모든 storage 작업은 이 타입을 반환하여 성공/실패를 명시적으로 표현합니다.
 */
sealed class StorageResult {
    /**
     * 성공적인 저장 결과
     * 
     * @property id 저장된 레코드의 ID
     * @property timestamp 저장 시점
     */
    data class Success(
        val id: String,
        val timestamp: Instant = Instant.now()
    ) : StorageResult()
    
    /**
     * 저장 실패 결과
     * 
     * @property error 에러 메시지
     * @property cause 원인 예외 (선택적)
     * @property timestamp 실패 시점
     */
    data class Failure(
        val error: String,
        val cause: Throwable? = null,
        val timestamp: Instant = Instant.now()
    ) : StorageResult()
    
    /**
     * 결과가 성공인지 확인
     */
    fun isSuccess(): Boolean = this is Success
    
    /**
     * 결과가 실패인지 확인
     */
    fun isFailure(): Boolean = this is Failure
    
    /**
     * 성공인 경우 ID 반환, 실패인 경우 null 반환
     */
    fun getIdOrNull(): String? = when (this) {
        is Success -> id
        is Failure -> null
    }
    
    /**
     * 성공인 경우 ID 반환, 실패인 경우 예외 발생
     */
    fun getIdOrThrow(): String = when (this) {
        is Success -> id
        is Failure -> throw IllegalStateException("Storage operation failed: $error", cause)
    }
}

/**
 * 배치 저장 작업의 결과
 * 
 * @property successful 성공적으로 저장된 레코드 ID 목록
 * @property failed 실패한 레코드의 인덱스와 에러 메시지 쌍
 * @property duration 전체 배치 처리 소요 시간
 * @property timestamp 배치 처리 완료 시점
 */
data class BatchStorageResult(
    val successful: List<String>,
    val failed: List<FailedRecord>,
    val duration: Duration,
    val timestamp: Instant = Instant.now()
) {
    /**
     * 전체 레코드 수
     */
    val totalCount: Int = successful.size + failed.size
    
    /**
     * 성공률 (0.0 ~ 1.0)
     */
    val successRate: Double = if (totalCount > 0) {
        successful.size.toDouble() / totalCount
    } else {
        0.0
    }
    
    /**
     * 처리량 (레코드/초)
     */
    val throughput: Double = if (duration.toMillis() > 0) {
        totalCount * 1000.0 / duration.toMillis()
    } else {
        0.0
    }
    
    /**
     * 모든 레코드가 성공했는지 확인
     */
    fun isFullSuccess(): Boolean = totalCount > 0 && failed.isEmpty()
    
    /**
     * 부분적으로 성공했는지 확인
     */
    fun isPartialSuccess(): Boolean = successful.isNotEmpty() && failed.isNotEmpty()
    
    /**
     * 모든 레코드가 실패했는지 확인
     */
    fun isFullFailure(): Boolean = totalCount > 0 && successful.isEmpty()
    
    /**
     * 실패한 레코드 정보
     * 
     * @property index 배치 내에서의 인덱스
     * @property error 에러 메시지
     * @property cause 원인 예외 (선택적)
     */
    data class FailedRecord(
        val index: Int,
        val error: String,
        val cause: Throwable? = null
    )
}