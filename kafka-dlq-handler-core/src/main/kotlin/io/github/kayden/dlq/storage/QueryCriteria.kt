package io.github.kayden.dlq.storage

import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import java.time.Instant

/**
 * 쿼리 조건을 정의하는 data class
 * 
 * DLQStorage에서 레코드를 조회할 때 사용되는 필터링 조건을 정의합니다.
 * 모든 필드는 선택적이며, 제공된 조건들은 AND 연산으로 결합됩니다.
 * 
 * @property statuses 필터링할 상태 목록
 * @property errorTypes 필터링할 에러 타입 목록
 * @property originalTopic 원본 토픽 필터
 * @property createdAfter 이 시간 이후에 생성된 레코드
 * @property createdBefore 이 시간 이전에 생성된 레코드
 * @property retryCountLessThan 재시도 횟수가 이 값보다 작은 레코드
 * @property retryCountGreaterThan 재시도 횟수가 이 값보다 큰 레코드
 * @property limit 최대 결과 개수
 * @property offset 결과 시작 위치 (페이징용)
 * @property sortBy 정렬 기준
 * @property sortOrder 정렬 순서
 */
data class QueryCriteria(
    val statuses: Set<DLQStatus>? = null,
    val errorTypes: Set<ErrorType>? = null,
    val originalTopic: String? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val retryCountLessThan: Int? = null,
    val retryCountGreaterThan: Int? = null,
    val limit: Int = 100,
    val offset: Int = 0,
    val sortBy: SortField = SortField.CREATED_AT,
    val sortOrder: SortOrder = SortOrder.DESC
) {
    init {
        require(limit > 0) { "Limit must be positive" }
        require(limit <= MAX_LIMIT) { "Limit cannot exceed $MAX_LIMIT" }
        require(offset >= 0) { "Offset must be non-negative" }
        retryCountLessThan?.let { 
            require(it >= 0) { "retryCountLessThan must be non-negative" }
        }
        retryCountGreaterThan?.let { 
            require(it >= 0) { "retryCountGreaterThan must be non-negative" }
        }
        
        if (createdAfter != null && createdBefore != null) {
            require(createdAfter.isBefore(createdBefore)) {
                "createdAfter must be before createdBefore"
            }
        }
    }
    
    companion object {
        const val MAX_LIMIT = 10000
        
        /**
         * 미처리 메시지 조회를 위한 기본 criteria
         */
        fun pending(limit: Int = 100): QueryCriteria = QueryCriteria(
            statuses = setOf(DLQStatus.PENDING),
            limit = limit
        )
        
        /**
         * 재시도 가능한 메시지 조회를 위한 criteria
         */
        fun retryable(maxRetries: Int = 3, limit: Int = 100): QueryCriteria = QueryCriteria(
            statuses = setOf(DLQStatus.PENDING, DLQStatus.RETRYING),
            retryCountLessThan = maxRetries,
            limit = limit
        )
        
        /**
         * 특정 토픽의 실패한 메시지 조회
         */
        fun failedByTopic(topic: String, limit: Int = 100): QueryCriteria = QueryCriteria(
            originalTopic = topic,
            statuses = setOf(DLQStatus.FAILED),
            limit = limit
        )
    }
}

/**
 * 정렬 필드
 */
enum class SortField {
    CREATED_AT,
    UPDATED_AT,
    RETRY_COUNT,
    ORIGINAL_OFFSET
}

/**
 * 정렬 순서
 */
enum class SortOrder {
    ASC,
    DESC
}