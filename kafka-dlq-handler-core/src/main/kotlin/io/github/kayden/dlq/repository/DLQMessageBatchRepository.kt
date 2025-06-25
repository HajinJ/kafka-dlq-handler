package io.github.kayden.dlq.repository

import io.github.kayden.dlq.domain.DLQMessageBatch
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * DLQ 메시지 배치 저장소
 */
@Repository
interface DLQMessageBatchRepository : JpaRepository<DLQMessageBatch, String> {
    
    /**
     * 날짜별 배치 조회
     */
    fun findByBatchDate(batchDate: String): List<DLQMessageBatch>
    
    /**
     * 토픽과 시간 범위로 조회
     */
    @Query("""
        SELECT b FROM DLQMessageBatch b
        WHERE b.topic = :topic
        AND b.batchTime BETWEEN :startTime AND :endTime
        ORDER BY b.batchTime DESC
    """)
    fun findByTopicAndTimeRange(
        @Param("topic") topic: String,
        @Param("startTime") startTime: Instant,
        @Param("endTime") endTime: Instant
    ): List<DLQMessageBatch>
    
    /**
     * 오래된 배치 삭제
     */
    @Query("""
        DELETE FROM DLQMessageBatch b
        WHERE b.batchTime < :before
    """)
    fun deleteOldBatches(@Param("before") before: Instant): Int
}