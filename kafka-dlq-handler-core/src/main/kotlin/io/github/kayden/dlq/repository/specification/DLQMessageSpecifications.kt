package io.github.kayden.dlq.repository.specification

import io.github.kayden.dlq.domain.DLQMessage
import io.github.kayden.dlq.domain.enums.DLQStatus
import io.github.kayden.dlq.domain.value.ReprocessingCriteria
import org.springframework.data.jpa.domain.Specification
import java.time.Instant
import javax.persistence.criteria.Predicate

/**
 * DLQ 메시지 동적 쿼리를 위한 Specification 모음
 * 
 * 복잡한 검색 조건을 조합하여 동적 쿼리를 생성할 수 있다.
 */
object DLQMessageSpecifications {
    
    /**
     * 원본 토픽으로 필터링
     */
    fun hasOriginalTopic(topic: String): Specification<DLQMessage> {
        return Specification { root, _, criteriaBuilder ->
            criteriaBuilder.equal(root.get<String>("originalTopic"), topic)
        }
    }
    
    /**
     * 여러 토픽 중 하나에 속하는 메시지 필터링
     */
    fun hasOriginalTopicIn(topics: Set<String>): Specification<DLQMessage> {
        return Specification { root, _, _ ->
            root.get<String>("originalTopic").`in`(topics)
        }
    }
    
    /**
     * 상태로 필터링
     */
    fun hasStatus(status: DLQStatus): Specification<DLQMessage> {
        return Specification { root, _, criteriaBuilder ->
            criteriaBuilder.equal(root.get<DLQStatus>("status"), status)
        }
    }
    
    /**
     * 여러 상태 중 하나에 속하는 메시지 필터링
     */
    fun hasStatusIn(statuses: Set<DLQStatus>): Specification<DLQMessage> {
        return Specification { root, _, _ ->
            root.get<DLQStatus>("status").`in`(statuses)
        }
    }
    
    /**
     * 에러 타입으로 필터링
     */
    fun hasErrorType(errorType: String): Specification<DLQMessage> {
        return Specification { root, _, criteriaBuilder ->
            criteriaBuilder.equal(root.get<String>("errorType"), errorType)
        }
    }
    
    /**
     * 여러 에러 타입 중 하나에 속하는 메시지 필터링
     */
    fun hasErrorTypeIn(errorTypes: Set<String>): Specification<DLQMessage> {
        return Specification { root, _, _ ->
            root.get<String>("errorType").`in`(errorTypes)
        }
    }
    
    /**
     * 특정 시간 이후에 생성된 메시지 필터링
     */
    fun createdAfter(instant: Instant): Specification<DLQMessage> {
        return Specification { root, _, criteriaBuilder ->
            criteriaBuilder.greaterThanOrEqualTo(
                root.get("createdAt"), 
                instant
            )
        }
    }
    
    /**
     * 특정 시간 이전에 생성된 메시지 필터링
     */
    fun createdBefore(instant: Instant): Specification<DLQMessage> {
        return Specification { root, _, criteriaBuilder ->
            criteriaBuilder.lessThanOrEqualTo(
                root.get("createdAt"), 
                instant
            )
        }
    }
    
    /**
     * 재시도 가능한 메시지 필터링
     * - PENDING 상태
     * - 재시도 횟수가 최대치 미만
     */
    fun isRetryable(): Specification<DLQMessage> {
        return Specification { root, _, criteriaBuilder ->
            val statusPredicate = criteriaBuilder.equal(
                root.get<DLQStatus>("status"), 
                DLQStatus.PENDING
            )
            val retryPredicate = criteriaBuilder.lessThan(
                root.get("retryCount"),
                root.get<Int>("maxRetries")
            )
            criteriaBuilder.and(statusPredicate, retryPredicate)
        }
    }
    
    /**
     * 만료된 메시지 필터링
     */
    fun isExpired(now: Instant = Instant.now()): Specification<DLQMessage> {
        return Specification { root, _, criteriaBuilder ->
            val expiresAtNotNull = criteriaBuilder.isNotNull(
                root.get<Instant>("expiresAt")
            )
            val expired = criteriaBuilder.lessThan(
                root.get("expiresAt"), 
                now
            )
            val notAlreadyExpired = criteriaBuilder.notEqual(
                root.get<DLQStatus>("status"), 
                DLQStatus.EXPIRED
            )
            criteriaBuilder.and(expiresAtNotNull, expired, notAlreadyExpired)
        }
    }
    
    /**
     * 다음 재시도 시간이 된 메시지 필터링
     */
    fun isReadyForRetry(now: Instant = Instant.now()): Specification<DLQMessage> {
        return Specification { root, _, criteriaBuilder ->
            val pendingStatus = criteriaBuilder.equal(
                root.get<DLQStatus>("status"), 
                DLQStatus.PENDING
            )
            val nextRetryNotNull = criteriaBuilder.isNotNull(
                root.get<Instant>("nextRetryAt")
            )
            val timeToRetry = criteriaBuilder.lessThanOrEqualTo(
                root.get("nextRetryAt"), 
                now
            )
            criteriaBuilder.and(pendingStatus, nextRetryNotNull, timeToRetry)
        }
    }
    
    /**
     * 메시지 키로 필터링
     */
    fun hasMessageKey(messageKey: String): Specification<DLQMessage> {
        return Specification { root, _, criteriaBuilder ->
            criteriaBuilder.equal(root.get<String>("messageKey"), messageKey)
        }
    }
    
    /**
     * 에러 클래스 패턴으로 필터링
     */
    fun hasErrorClassLike(pattern: String): Specification<DLQMessage> {
        return Specification { root, _, criteriaBuilder ->
            criteriaBuilder.like(
                root.get("errorClass"), 
                "%$pattern%"
            )
        }
    }
    
    /**
     * ReprocessingCriteria를 기반으로 복합 필터링
     */
    fun matchesCriteria(criteria: ReprocessingCriteria): Specification<DLQMessage> {
        return Specification { root, query, criteriaBuilder ->
            val predicates = mutableListOf<Predicate>()
            
            // 토픽 필터
            criteria.topics?.let { topics ->
                predicates.add(root.get<String>("originalTopic").`in`(topics))
            }
            
            // 에러 타입 필터
            criteria.errorTypes?.let { errorTypes ->
                predicates.add(root.get<String>("errorType").`in`(errorTypes))
            }
            
            // 기간 필터
            criteria.getAfterInstant()?.let { after ->
                predicates.add(
                    criteriaBuilder.lessThanOrEqualTo(
                        root.get("createdAt"), 
                        after
                    )
                )
            }
            
            // 날짜 필터
            criteria.beforeDate?.let { before ->
                predicates.add(
                    criteriaBuilder.lessThanOrEqualTo(
                        root.get("createdAt"), 
                        before
                    )
                )
            }
            
            // 상태 필터
            predicates.add(root.get<DLQStatus>("status").`in`(criteria.statuses))
            
            criteriaBuilder.and(*predicates.toTypedArray())
        }
    }
    
    /**
     * 여러 Specification을 AND 조건으로 결합
     */
    fun and(vararg specs: Specification<DLQMessage>): Specification<DLQMessage> {
        return specs.reduce { acc, spec -> acc.and(spec) }
    }
    
    /**
     * 여러 Specification을 OR 조건으로 결합
     */
    fun or(vararg specs: Specification<DLQMessage>): Specification<DLQMessage> {
        return specs.reduce { acc, spec -> acc.or(spec) }
    }
}