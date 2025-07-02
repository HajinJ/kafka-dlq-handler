package io.github.kayden.dlq.core.model

import java.util.UUID

/**
 * DLQ 메시지를 나타내는 핵심 도메인 모델.
 * 
 * 이 클래스는 프레임워크 독립적인 순수 Kotlin data class로 구현되었으며,
 * 고성능 메시지 처리를 위해 ByteArray를 사용하여 zero-copy 연산을 지원한다.
 * 
 * @property id 메시지의 고유 식별자
 * @property messageKey Kafka 메시지 키
 * @property originalTopic 원본 메시지가 발행된 토픽
 * @property originalPartition 원본 메시지의 파티션 번호
 * @property originalOffset 원본 메시지의 오프셋
 * @property payload 메시지 페이로드 (ByteArray로 저장하여 zero-copy 지원)
 * @property headers 메시지 헤더 맵 (key-value 모두 ByteArray)
 * @property errorClass 발생한 예외의 클래스명
 * @property errorMessage 에러 메시지
 * @property errorType 에러 타입 분류
 * @property stackTrace 스택 트레이스 (디버깅용)
 * @property status DLQ 메시지의 현재 상태
 * @property retryCount 재시도 횟수
 * @property lastRetryAt 마지막 재시도 시간 (epoch milliseconds)
 * @property processingMetadata 처리 관련 메타데이터
 * @property createdAt 생성 시간 (epoch milliseconds)
 * @property updatedAt 마지막 수정 시간 (epoch milliseconds)
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
        require(originalTopic.isNotBlank()) { "Original topic must not be blank" }
        require(originalPartition >= 0) { "Original partition must not be negative" }
        require(originalOffset >= 0) { "Original offset must not be negative" }
        require(retryCount >= 0) { "Retry count must not be negative" }
        require(payload.isNotEmpty()) { "Payload must not be empty" }
    }

    /**
     * 지연 초기화를 통한 페이로드 문자열 변환.
     * 성능 최적화를 위해 실제로 필요한 경우에만 변환이 수행된다.
     */
    val payloadAsString: String by lazy { 
        String(payload, Charsets.UTF_8) 
    }
    
    /**
     * 지연 초기화를 통한 헤더 문자열 맵 변환.
     * 성능 최적화를 위해 실제로 필요한 경우에만 변환이 수행된다.
     */
    val headersAsStringMap: Map<String, String> by lazy {
        headers.mapValues { (_, value) -> String(value, Charsets.UTF_8) }
    }
    
    /**
     * 메시지 키의 문자열 변환 (null-safe).
     */
    val messageKeyAsString: String? by lazy {
        messageKey
    }
    
    /**
     * 재처리 가능 여부를 판단한다.
     * 
     * @param maxRetries 최대 재시도 횟수
     * @return 재처리 가능하면 true
     */
    fun canRetry(maxRetries: Int = 3): Boolean =
        status in listOf(DLQStatus.PENDING, DLQStatus.RETRYING, DLQStatus.FAILED) &&
        retryCount < maxRetries &&
        errorType != ErrorType.PERMANENT_FAILURE
    
    /**
     * 재시도를 위한 새로운 인스턴스를 생성한다.
     * 
     * @return 재시도 상태로 업데이트된 새 인스턴스
     */
    fun withRetry(): DLQRecord = copy(
        status = DLQStatus.RETRYING,
        retryCount = retryCount + 1,
        lastRetryAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )
    
    /**
     * 상태를 업데이트한 새로운 인스턴스를 생성한다.
     * 
     * @param newStatus 새로운 상태
     * @return 상태가 업데이트된 새 인스턴스
     */
    fun withStatus(newStatus: DLQStatus): DLQRecord = copy(
        status = newStatus,
        updatedAt = System.currentTimeMillis()
    )
    
    /**
     * 처리 메타데이터를 업데이트한 새로운 인스턴스를 생성한다.
     * 
     * @param metadata 새로운 처리 메타데이터
     * @return 메타데이터가 업데이트된 새 인스턴스
     */
    fun withProcessingMetadata(metadata: ProcessingMetadata): DLQRecord = copy(
        processingMetadata = metadata,
        updatedAt = System.currentTimeMillis()
    )
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DLQRecord) return false
        return id == other.id
    }
    
    override fun hashCode(): Int = id.hashCode()
    
    override fun toString(): String {
        return "DLQRecord(" +
            "id='$id', " +
            "originalTopic='$originalTopic', " +
            "status=$status, " +
            "retryCount=$retryCount, " +
            "errorType=$errorType" +
            ")"
    }
}