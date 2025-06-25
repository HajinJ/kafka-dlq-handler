package io.github.kayden.dlq.repository

import io.github.kayden.dlq.domain.DLQMessage
import io.github.kayden.dlq.domain.enums.DLQStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * DLQ 메시지 저장소 인터페이스
 * 
 * JPA Repository와 Specification을 지원하여 동적 쿼리 구성이 가능하다.
 */
@Repository
interface DLQMessageRepository : 
    JpaRepository<DLQMessage, Long>, 
    JpaSpecificationExecutor<DLQMessage> {
    
    /**
     * 원본 토픽으로 메시지를 조회한다.
     */
    fun findByOriginalTopic(originalTopic: String): List<DLQMessage>
    
    /**
     * 상태로 메시지를 조회한다.
     */
    fun findByStatus(status: DLQStatus): List<DLQMessage>
    
    /**
     * 상태로 메시지를 페이징 조회한다.
     */
    fun findByStatus(status: DLQStatus, pageable: Pageable): Page<DLQMessage>
    
    /**
     * 상태별 메시지 개수를 조회한다.
     */
    fun countByStatus(status: DLQStatus): Long
    
    /**
     * 특정 토픽의 특정 상태 메시지를 조회한다.
     */
    fun findByOriginalTopicAndStatus(
        originalTopic: String,
        status: DLQStatus
    ): List<DLQMessage>
    
    /**
     * 재처리 가능한 메시지를 조회한다.
     * PENDING 상태이고 재시도 횟수가 최대치를 넘지 않은 메시지
     */
    @Query("""
        SELECT m FROM DLQMessage m
        WHERE m.status = :status
        AND m.retryCount < m.maxRetries
        ORDER BY m.createdAt ASC
    """)
    fun findRetryableMessages(
        @Param("status") status: DLQStatus = DLQStatus.PENDING,
        pageable: Pageable
    ): Page<DLQMessage>
    
    /**
     * 최근 메시지를 상태별로 조회한다.
     * N+1 문제 방지를 위해 EntityGraph 사용
     */
    @EntityGraph(attributePaths = ["headers", "metadata"])
    @Query("""
        SELECT m FROM DLQMessage m
        WHERE m.status = :status
        AND m.createdAt >= :since
        ORDER BY m.createdAt DESC
    """)
    fun findRecentByStatus(
        @Param("status") status: DLQStatus,
        @Param("since") since: Instant,
        pageable: Pageable
    ): Page<DLQMessage>
    
    /**
     * 만료된 메시지를 조회한다.
     */
    @Query("""
        SELECT m FROM DLQMessage m
        WHERE m.expiresAt IS NOT NULL
        AND m.expiresAt < :now
        AND m.status NOT IN (:excludedStatuses)
    """)
    fun findExpiredMessages(
        @Param("now") now: Instant = Instant.now(),
        @Param("excludedStatuses") excludedStatuses: Set<DLQStatus> = setOf(
            DLQStatus.SUCCESS,
            DLQStatus.EXPIRED
        )
    ): List<DLQMessage>
    
    /**
     * 다음 재시도 시간이 된 메시지를 조회한다.
     */
    @Query("""
        SELECT m FROM DLQMessage m
        WHERE m.status = :status
        AND m.nextRetryAt IS NOT NULL
        AND m.nextRetryAt <= :now
        ORDER BY m.nextRetryAt ASC
    """)
    fun findMessagesReadyForRetry(
        @Param("status") status: DLQStatus = DLQStatus.PENDING,
        @Param("now") now: Instant = Instant.now(),
        pageable: Pageable
    ): Page<DLQMessage>
    
    /**
     * 배치로 메시지 상태를 업데이트한다.
     */
    @Modifying
    @Query("""
        UPDATE DLQMessage m
        SET m.status = :newStatus,
            m.updatedAt = :updatedAt
        WHERE m.id IN :ids
    """)
    fun updateStatusBatch(
        @Param("ids") ids: List<Long>,
        @Param("newStatus") newStatus: DLQStatus,
        @Param("updatedAt") updatedAt: Instant = Instant.now()
    ): Int
    
    /**
     * 오래된 성공 메시지를 삭제한다.
     */
    @Modifying
    @Query("""
        DELETE FROM DLQMessage m
        WHERE m.status = :status
        AND m.updatedAt < :before
    """)
    fun deleteOldSuccessMessages(
        @Param("status") status: DLQStatus = DLQStatus.SUCCESS,
        @Param("before") before: Instant
    ): Int
    
    /**
     * 토픽별 에러 타입 통계를 조회한다.
     */
    @Query("""
        SELECT m.originalTopic as topic,
               m.errorType as errorType,
               COUNT(m) as count
        FROM DLQMessage m
        WHERE m.createdAt >= :since
        GROUP BY m.originalTopic, m.errorType
        ORDER BY COUNT(m) DESC
    """)
    fun findErrorStatistics(
        @Param("since") since: Instant
    ): List<ErrorStatistic>
    
    /**
     * 메시지 키와 토픽으로 중복 메시지를 확인한다.
     */
    fun existsByMessageKeyAndOriginalTopicAndCreatedAtAfter(
        messageKey: String,
        originalTopic: String,
        after: Instant
    ): Boolean
}

/**
 * 에러 통계 프로젝션 인터페이스
 */
interface ErrorStatistic {
    val topic: String
    val errorType: String?
    val count: Long
}