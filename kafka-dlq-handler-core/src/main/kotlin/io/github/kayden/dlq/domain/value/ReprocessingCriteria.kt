package io.github.kayden.dlq.domain.value

import io.github.kayden.dlq.domain.enums.DLQStatus
import java.time.Duration
import java.time.Instant

/**
 * 메시지 재처리 대상을 선별하기 위한 조건을 정의하는 값 객체
 * 
 * @property topics 재처리할 토픽 목록 (null이면 모든 토픽)
 * @property errorTypes 재처리할 에러 타입 목록 (null이면 모든 타입)
 * @property afterDuration 이 기간 이상 경과한 메시지만 재처리
 * @property beforeDate 이 날짜 이전의 메시지만 재처리
 * @property statuses 재처리할 메시지의 상태 목록
 */
data class ReprocessingCriteria(
    val topics: Set<String>? = null,
    val errorTypes: Set<String>? = null,
    val afterDuration: Duration? = null,
    val beforeDate: Instant? = null,
    val statuses: Set<DLQStatus> = setOf(DLQStatus.PENDING)
) {
    init {
        afterDuration?.let {
            require(!it.isNegative) { 
                "After duration must not be negative" 
            }
        }
        
        require(statuses.isNotEmpty()) { 
            "At least one status must be specified" 
        }
        
        require(!statuses.contains(DLQStatus.PROCESSING)) { 
            "Cannot reprocess messages in PROCESSING status" 
        }
    }
    
    companion object {
        /**
         * 모든 PENDING 메시지를 재처리하는 기본 조건
         */
        val ALL_PENDING = ReprocessingCriteria()
        
        /**
         * 실패한 메시지만 재처리하는 조건
         */
        val FAILED_ONLY = ReprocessingCriteria(
            statuses = setOf(DLQStatus.FAILED)
        )
        
        /**
         * 오래된 메시지를 재처리하는 조건
         */
        fun olderThan(duration: Duration) = ReprocessingCriteria(
            afterDuration = duration
        )
        
        /**
         * 특정 토픽의 메시지만 재처리하는 조건
         */
        fun forTopics(vararg topics: String) = ReprocessingCriteria(
            topics = topics.toSet()
        )
    }
    
    /**
     * 현재 시간 기준으로 afterDuration 조건을 만족하는 시간을 계산한다.
     */
    fun getAfterInstant(): Instant? = 
        afterDuration?.let { Instant.now().minus(it) }
}