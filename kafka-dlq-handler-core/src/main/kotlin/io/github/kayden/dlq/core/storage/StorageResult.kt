package io.github.kayden.dlq.core.storage

import io.github.kayden.dlq.core.model.DLQRecord

/**
 * 저장소 작업 결과를 나타내는 sealed class.
 * 
 * 성공과 실패 케이스를 타입 안전하게 처리할 수 있다.
 * 
 * @since 0.1.0
 */
sealed class StorageResult {
    
    /**
     * 성공적인 저장소 작업 결과.
     * 
     * @property recordId 처리된 레코드 ID
     * @property record 처리된 레코드 (조회 시)
     * @property message 성공 메시지 (optional)
     */
    data class Success(
        val recordId: String,
        val record: DLQRecord? = null,
        val message: String? = null
    ) : StorageResult()
    
    /**
     * 실패한 저장소 작업 결과.
     * 
     * @property recordId 처리 시도한 레코드 ID
     * @property errorType 에러 타입
     * @property message 에러 메시지
     * @property exception 발생한 예외 (optional)
     */
    data class Failure(
        val recordId: String,
        val errorType: StorageErrorType,
        val message: String,
        val exception: Throwable? = null
    ) : StorageResult()
    
    /**
     * 레코드를 찾을 수 없는 경우.
     * 
     * @property recordId 찾을 수 없는 레코드 ID
     * @property message 메시지
     */
    data class NotFound(
        val recordId: String,
        val message: String = "Record not found"
    ) : StorageResult()
    
    /**
     * 동시성 충돌이 발생한 경우.
     * 
     * @property recordId 충돌이 발생한 레코드 ID
     * @property currentVersion 현재 버전
     * @property expectedVersion 예상했던 버전
     * @property message 메시지
     */
    data class ConcurrencyConflict(
        val recordId: String,
        val currentVersion: Long,
        val expectedVersion: Long,
        val message: String = "Concurrent modification detected"
    ) : StorageResult()
    
    /**
     * 작업이 성공했는지 확인한다.
     */
    fun isSuccess(): Boolean = this is Success
    
    /**
     * 작업이 실패했는지 확인한다.
     */
    fun isFailure(): Boolean = !isSuccess()
    
    /**
     * 성공한 경우 결과를 반환하고, 실패한 경우 null을 반환한다.
     */
    fun getOrNull(): DLQRecord? = when (this) {
        is Success -> record
        else -> null
    }
    
    /**
     * 성공한 경우 결과를 반환하고, 실패한 경우 예외를 발생시킨다.
     */
    fun getOrThrow(): DLQRecord = when (this) {
        is Success -> record ?: throw IllegalStateException("No record in success result")
        is Failure -> throw StorageException(message, exception)
        is NotFound -> throw NoSuchElementException(message)
        is ConcurrencyConflict -> throw StorageException(message)
    }
    
    /**
     * 결과를 다른 타입으로 변환한다.
     */
    inline fun <R> map(transform: (DLQRecord) -> R): StorageResult {
        return when (this) {
            is Success -> {
                record?.let {
                    Success(recordId, transform(it) as? DLQRecord, message)
                } ?: this
            }
            else -> this
        }
    }
    
    /**
     * 실패한 경우 복구 로직을 실행한다.
     */
    inline fun recover(recovery: (StorageResult) -> StorageResult): StorageResult {
        return when (this) {
            is Success -> this
            else -> recovery(this)
        }
    }
    
    /**
     * 성공한 경우 추가 작업을 수행한다.
     */
    inline fun onSuccess(action: (Success) -> Unit): StorageResult {
        if (this is Success) action(this)
        return this
    }
    
    /**
     * 실패한 경우 추가 작업을 수행한다.
     */
    inline fun onFailure(action: (StorageResult) -> Unit): StorageResult {
        if (this !is Success) action(this)
        return this
    }
}

/**
 * 저장소 에러 타입을 정의하는 열거형.
 */
enum class StorageErrorType {
    /**
     * 연결 실패.
     */
    CONNECTION_ERROR,
    
    /**
     * 타임아웃.
     */
    TIMEOUT,
    
    /**
     * 데이터 유효성 검증 실패.
     */
    VALIDATION_ERROR,
    
    /**
     * 용량 부족.
     */
    CAPACITY_EXCEEDED,
    
    /**
     * 권한 부족.
     */
    ACCESS_DENIED,
    
    /**
     * 직렬화/역직렬화 실패.
     */
    SERIALIZATION_ERROR,
    
    /**
     * 트랜잭션 실패.
     */
    TRANSACTION_ERROR,
    
    /**
     * 알 수 없는 에러.
     */
    UNKNOWN
}

/**
 * 저장소 관련 예외.
 */
class StorageException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * 배치 작업 결과를 나타내는 data class.
 * 
 * @property results 개별 작업 결과 목록
 * @property successCount 성공한 작업 수
 * @property failureCount 실패한 작업 수
 */
data class BatchStorageResult(
    val results: List<StorageResult>
) {
    val successCount: Int = results.count { it is StorageResult.Success }
    val failureCount: Int = results.size - successCount
    val isFullSuccess: Boolean = failureCount == 0
    val isPartialSuccess: Boolean = successCount > 0 && failureCount > 0
    val isFullFailure: Boolean = successCount == 0
    
    /**
     * 성공한 결과만 반환한다.
     */
    fun successes(): List<StorageResult.Success> =
        results.filterIsInstance<StorageResult.Success>()
    
    /**
     * 실패한 결과만 반환한다.
     */
    fun failures(): List<StorageResult> =
        results.filter { it !is StorageResult.Success }
    
    /**
     * 성공한 레코드 ID 목록을 반환한다.
     */
    fun successfulIds(): List<String> =
        successes().map { it.recordId }
    
    /**
     * 실패한 레코드 ID 목록을 반환한다.
     */
    fun failedIds(): List<String> =
        failures().mapNotNull {
            when (it) {
                is StorageResult.Failure -> it.recordId
                is StorageResult.NotFound -> it.recordId
                is StorageResult.ConcurrencyConflict -> it.recordId
                else -> null
            }
        }
}