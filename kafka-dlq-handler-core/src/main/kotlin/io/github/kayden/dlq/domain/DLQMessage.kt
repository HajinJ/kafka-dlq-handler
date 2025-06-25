package io.github.kayden.dlq.domain

import io.github.kayden.dlq.domain.enums.DLQStatus
import java.time.Instant
import jakarta.persistence.*

/**
 * DLQ 메시지 엔티티
 * 
 * Kafka DLQ에서 수집된 실패 메시지를 저장하고 관리한다.
 * 재처리 정책과 메타데이터를 포함하여 메시지의 전체 수명주기를 추적한다.
 */
@Entity
@Table(
    name = "dlq_messages",
    indexes = [
        Index(name = "idx_original_topic", columnList = "original_topic"),
        Index(name = "idx_status", columnList = "status"),
        Index(name = "idx_created_at", columnList = "created_at"),
        Index(name = "idx_error_type", columnList = "error_type")
    ]
)
data class DLQMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "message_key", length = 255)
    val messageKey: String?,
    
    @Column(name = "original_topic", nullable = false, length = 255)
    val originalTopic: String,
    
    @Column(name = "original_partition")
    val originalPartition: Int?,
    
    @Column(name = "original_offset")
    val originalOffset: Long?,
    
    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    val payload: String,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    val status: DLQStatus = DLQStatus.PENDING,
    
    @Column(name = "error_class", length = 500)
    val errorClass: String?,
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    val errorMessage: String?,
    
    @Column(name = "error_type", length = 50)
    val errorType: String?,
    
    @Column(name = "retry_count", nullable = false)
    val retryCount: Int = 0,
    
    @Column(name = "max_retries")
    val maxRetries: Int = 3,
    
    @ElementCollection
    @CollectionTable(
        name = "dlq_message_headers",
        joinColumns = [JoinColumn(name = "message_id")]
    )
    @MapKeyColumn(name = "header_key")
    @Column(name = "header_value")
    val headers: Map<String, String> = emptyMap(),
    
    @ElementCollection
    @CollectionTable(
        name = "dlq_message_metadata",
        joinColumns = [JoinColumn(name = "message_id")]
    )
    @MapKeyColumn(name = "metadata_key")
    @Column(name = "metadata_value")
    val metadata: Map<String, String> = emptyMap(),
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
    
    @Column(name = "last_retry_at")
    val lastRetryAt: Instant? = null,
    
    @Column(name = "next_retry_at")
    val nextRetryAt: Instant? = null,
    
    @Column(name = "expires_at")
    val expiresAt: Instant? = null,
    
    @Version
    @Column(name = "version")
    val version: Long = 0
) {
    init {
        require(originalTopic.isNotBlank()) { 
            "Original topic must not be blank" 
        }
        require(payload.isNotBlank()) { 
            "Payload must not be blank" 
        }
        require(retryCount >= 0) { 
            "Retry count must not be negative" 
        }
        require(maxRetries >= 0) { 
            "Max retries must not be negative" 
        }
    }
    
    /**
     * 메시지가 재시도 가능한지 확인한다.
     * PENDING 상태이고 재시도 횟수가 최대치를 넘지 않았을 때만 true를 반환한다.
     */
    fun canRetry(): Boolean =
        status == DLQStatus.PENDING && retryCount < maxRetries
    
    /**
     * 재시도를 위한 새로운 인스턴스를 생성한다.
     * 상태를 PROCESSING으로 변경하고 재시도 횟수를 증가시킨다.
     */
    fun withRetry(): DLQMessage = copy(
        status = DLQStatus.PROCESSING,
        retryCount = retryCount + 1,
        lastRetryAt = Instant.now(),
        updatedAt = Instant.now()
    )
    
    /**
     * 새로운 상태로 인스턴스를 생성한다.
     */
    fun withStatus(newStatus: DLQStatus): DLQMessage = copy(
        status = newStatus,
        updatedAt = Instant.now()
    )
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as DLQMessage
        
        if (id != other.id) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}