package io.github.kayden.dlq.domain

import io.github.kayden.dlq.domain.enums.DLQStatus
import jakarta.persistence.*
import java.time.Instant

/**
 * DLQ 메시지 요약 정보 (고성능 조회용)
 * 
 * 핵심 정보만 포함하여 빠른 조회와 대량 처리에 최적화된 엔티티.
 * payload와 headers는 별도 저장소에 보관한다.
 */
@Entity
@Table(
    name = "dlq_message_summary",
    indexes = [
        Index(name = "idx_topic_created", columnList = "original_topic,created_at"),
        Index(name = "idx_status_created", columnList = "status,created_at"),
        Index(name = "idx_error_type", columnList = "error_type"),
        Index(name = "idx_created_at", columnList = "created_at")
    ]
)
data class DLQMessageSummary(
    @Id
    val id: String,  // UUID 또는 Kafka offset 기반
    
    @Column(name = "original_topic", nullable = false, length = 255)
    val originalTopic: String,
    
    @Column(name = "partition_id")
    val partitionId: Int,
    
    @Column(name = "offset_value")
    val offsetValue: Long,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    val status: DLQStatus = DLQStatus.PENDING,
    
    @Column(name = "error_type", length = 50)
    val errorType: String?,
    
    @Column(name = "retry_count", nullable = false)
    val retryCount: Int = 0,
    
    @Column(name = "message_size")
    val messageSize: Int,  // payload 크기 (bytes)
    
    @Column(name = "payload_location", length = 500)
    val payloadLocation: String?,  // S3 URL 또는 다른 저장소 위치
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(name = "next_retry_at")
    val nextRetryAt: Instant? = null,
    
    // 파티셔닝을 위한 필드
    @Column(name = "created_date", nullable = false)
    val createdDate: String = createdAt.toString().substring(0, 10)  // YYYY-MM-DD
) {
    companion object {
        const val LARGE_MESSAGE_THRESHOLD = 1024 * 100  // 100KB
    }
    
    fun isLargeMessage(): Boolean = messageSize > LARGE_MESSAGE_THRESHOLD
}