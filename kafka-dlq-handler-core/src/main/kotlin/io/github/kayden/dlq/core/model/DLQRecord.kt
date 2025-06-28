package io.github.kayden.dlq.core.model

import java.util.UUID

/**
 * Core DLQ 레코드 모델 - 프레임워크 독립적
 * 
 * Dead Letter Queue에 저장되는 메시지를 표현하는 핵심 데이터 모델.
 * JPA나 Spring 의존성 없이 순수 Kotlin data class로 구현됨.
 * 
 * @property id 메시지의 고유 식별자
 * @property messageKey Kafka 메시지의 키
 * @property originalTopic 원본 토픽명
 * @property originalPartition 원본 파티션 번호
 * @property originalOffset 원본 오프셋
 * @property payload 메시지 페이로드 (ByteArray로 저장하여 zero-copy 지원)
 * @property headers 메시지 헤더 정보
 * @property errorClass 발생한 에러의 클래스명
 * @property errorMessage 에러 메시지
 * @property errorType 에러 타입 분류
 * @property stackTrace 에러 스택 트레이스
 * @property status DLQ 메시지 상태
 * @property retryCount 재시도 횟수
 * @property lastRetryAt 마지막 재시도 시각 (epoch millis)
 * @property processingMetadata 처리 관련 메타데이터
 * @property createdAt 생성 시각 (epoch millis)
 * @property updatedAt 수정 시각 (epoch millis)
 * 
 * @since 0.1.0
 */
data class DLQRecord(
    val id: String = UUID.randomUUID().toString(),
    val messageKey: String?,
    val originalTopic: String,
    val originalPartition: Int,
    val originalOffset: Long,
    val payload: ByteArray,
    val headers: Map<String, ByteArray>,
    val errorClass: String?,
    val errorMessage: String?,
    val errorType: ErrorType,
    val stackTrace: String?,
    val status: DLQStatus = DLQStatus.PENDING,
    val retryCount: Int = 0,
    val lastRetryAt: Long? = null,
    val processingMetadata: ProcessingMetadata? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    init {
        require(originalTopic.isNotBlank()) { 
            "Original topic must not be blank" 
        }
        require(originalPartition >= 0) { 
            "Original partition must not be negative" 
        }
        require(originalOffset >= 0) { 
            "Original offset must not be negative" 
        }
        require(retryCount >= 0) { 
            "Retry count must not be negative" 
        }
        require(createdAt > 0) { 
            "Created timestamp must be positive" 
        }
        require(updatedAt >= createdAt) { 
            "Updated timestamp must not be before created timestamp" 
        }
    }
    
    /**
     * ByteArray를 사용하므로 equals와 hashCode를 id 기반으로 재정의
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DLQRecord) return false
        return id == other.id
    }
    
    override fun hashCode(): Int = id.hashCode()
    
    /**
     * 페이로드를 문자열로 변환 (lazy evaluation)
     */
    val payloadAsString: String by lazy { 
        String(payload, Charsets.UTF_8) 
    }
    
    /**
     * 헤더를 문자열 맵으로 변환 (lazy evaluation)
     */
    val headersAsStringMap: Map<String, String> by lazy {
        headers.mapValues { String(it.value, Charsets.UTF_8) }
    }
    
    /**
     * 재처리 가능 여부 확인
     */
    fun canRetry(maxRetries: Int = 3): Boolean =
        status == DLQStatus.PENDING && retryCount < maxRetries
    
    /**
     * 재시도를 위한 새로운 레코드 생성
     */
    fun withRetry(): DLQRecord = copy(
        status = DLQStatus.RETRYING,
        retryCount = retryCount + 1,
        lastRetryAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )
    
    /**
     * 상태 업데이트를 위한 새로운 레코드 생성
     */
    fun withStatus(newStatus: DLQStatus): DLQRecord = copy(
        status = newStatus,
        updatedAt = System.currentTimeMillis()
    )
    
    /**
     * 메타데이터 업데이트를 위한 새로운 레코드 생성
     */
    fun withMetadata(metadata: ProcessingMetadata): DLQRecord = copy(
        processingMetadata = metadata,
        updatedAt = System.currentTimeMillis()
    )
}