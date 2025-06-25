package io.github.kayden.dlq.repository

import io.github.kayden.dlq.domain.DLQMessageSummary
import io.github.kayden.dlq.domain.enums.DLQStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * DLQ 메시지 요약 저장소
 * 
 * 고성능 조회와 파티셔닝을 지원하는 리포지토리
 */
@Repository
interface DLQMessageSummaryRepository : JpaRepository<DLQMessageSummary, String> {
    
    /**
     * 날짜별 파티션 조회
     */
    @Query("""
        SELECT s FROM DLQMessageSummary s
        WHERE s.createdDate = :date
        AND s.status = :status
        ORDER BY s.createdAt DESC
    """)
    fun findByDateAndStatus(
        @Param("date") date: String,
        @Param("status") status: DLQStatus
    ): List<DLQMessageSummary>
    
    /**
     * 재처리 대상 조회 (인덱스 최적화)
     */
    @Query(
        value = """
            SELECT * FROM dlq_message_summary
            WHERE status = 'PENDING'
            AND next_retry_at <= :now
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findMessagesForRetry(
        @Param("now") now: Instant,
        @Param("limit") limit: Int
    ): List<DLQMessageSummary>
    
    /**
     * 배치 상태 업데이트
     */
    @Modifying
    @Query("""
        UPDATE DLQMessageSummary s
        SET s.status = :newStatus
        WHERE s.id IN :ids
    """)
    fun updateStatusBatch(
        @Param("ids") ids: List<String>,
        @Param("newStatus") newStatus: DLQStatus
    ): Int
}