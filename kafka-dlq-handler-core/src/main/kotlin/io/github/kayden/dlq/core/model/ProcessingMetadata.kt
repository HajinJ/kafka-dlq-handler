package io.github.kayden.dlq.core.model

/**
 * DLQ 메시지 처리와 관련된 메타데이터
 * 
 * 메시지 처리 과정에서 발생하는 추가 정보를 저장하는 불변 객체.
 * 재처리 시도, 처리 환경, 추적 정보 등을 포함함.
 * 
 * @property processingAttempts 처리 시도 기록
 * @property tags 분류 및 필터링을 위한 태그
 * @property customAttributes 확장 가능한 사용자 정의 속성
 * @property processorInfo 처리기 정보
 * @property retryPolicy 재처리 정책
 * 
 * @since 0.1.0
 */
data class ProcessingMetadata(
    val processingAttempts: List<ProcessingAttempt> = emptyList(),
    val tags: Set<String> = emptySet(),
    val customAttributes: Map<String, String> = emptyMap(),
    val processorInfo: ProcessorInfo? = null,
    val retryPolicy: RetryPolicy? = null
) {
    /**
     * 태그 추가
     */
    fun withTag(tag: String): ProcessingMetadata = copy(
        tags = tags + tag
    )
    
    /**
     * 여러 태그 추가
     */
    fun withTags(newTags: Set<String>): ProcessingMetadata = copy(
        tags = tags + newTags
    )
    
    /**
     * 커스텀 속성 추가
     */
    fun withAttribute(key: String, value: String): ProcessingMetadata = copy(
        customAttributes = customAttributes + (key to value)
    )
    
    /**
     * 처리 시도 기록 추가
     */
    fun withProcessingAttempt(attempt: ProcessingAttempt): ProcessingMetadata = copy(
        processingAttempts = processingAttempts + attempt
    )
    
    /**
     * 마지막 처리 시도 정보
     */
    val lastAttempt: ProcessingAttempt? = processingAttempts.lastOrNull()
    
    /**
     * 총 처리 시도 횟수
     */
    val totalAttempts: Int = processingAttempts.size
    
    /**
     * 성공한 처리 시도 횟수
     */
    val successfulAttempts: Int = processingAttempts.count { it.successful }
}

/**
 * 개별 처리 시도 정보
 * 
 * @property attemptNumber 시도 번호 (1부터 시작)
 * @property timestamp 시도 시각 (epoch millis)
 * @property successful 성공 여부
 * @property errorMessage 실패 시 에러 메시지
 * @property processingDuration 처리 소요 시간 (millis)
 * @property processorId 처리기 식별자
 */
data class ProcessingAttempt(
    val attemptNumber: Int,
    val timestamp: Long,
    val successful: Boolean,
    val errorMessage: String? = null,
    val processingDuration: Long? = null,
    val processorId: String? = null
) {
    init {
        require(attemptNumber > 0) { 
            "Attempt number must be positive" 
        }
        require(timestamp > 0) { 
            "Timestamp must be positive" 
        }
        require(!successful || errorMessage == null) { 
            "Successful attempt should not have error message" 
        }
        processingDuration?.let {
            require(it >= 0) { 
                "Processing duration must not be negative" 
            }
        }
    }
}

/**
 * 처리기 정보
 * 
 * @property processorId 처리기 고유 식별자
 * @property processorType 처리기 타입
 * @property version 처리기 버전
 * @property hostname 처리기가 실행 중인 호스트명
 */
data class ProcessorInfo(
    val processorId: String,
    val processorType: String,
    val version: String,
    val hostname: String? = null
) {
    init {
        require(processorId.isNotBlank()) { 
            "Processor ID must not be blank" 
        }
        require(processorType.isNotBlank()) { 
            "Processor type must not be blank" 
        }
        require(version.isNotBlank()) { 
            "Version must not be blank" 
        }
    }
}

