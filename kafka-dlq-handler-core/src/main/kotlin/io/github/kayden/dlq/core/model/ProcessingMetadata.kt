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

/**
 * 재처리 정책
 * 
 * @property maxRetries 최대 재시도 횟수
 * @property backoffStrategy 백오프 전략
 * @property retryableErrorTypes 재시도 가능한 에러 타입
 * @property retryDelay 재시도 지연 시간 (millis)
 * @property maxRetryDelay 최대 재시도 지연 시간 (millis)
 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val backoffStrategy: BackoffStrategy = BackoffStrategy.EXPONENTIAL,
    val retryableErrorTypes: Set<ErrorType> = setOf(
        ErrorType.TRANSIENT,
        ErrorType.TIMEOUT,
        ErrorType.INFRASTRUCTURE,
        ErrorType.RESOURCE_EXHAUSTED
    ),
    val retryDelay: Long = 1000,
    val maxRetryDelay: Long = 60000
) {
    init {
        require(maxRetries >= 0) { 
            "Max retries must not be negative" 
        }
        require(retryDelay > 0) { 
            "Retry delay must be positive" 
        }
        require(maxRetryDelay >= retryDelay) { 
            "Max retry delay must not be less than retry delay" 
        }
    }
    
    /**
     * 특정 시도 횟수에 대한 지연 시간 계산
     */
    fun calculateDelay(attemptNumber: Int): Long {
        val delay = when (backoffStrategy) {
            BackoffStrategy.FIXED -> retryDelay
            BackoffStrategy.LINEAR -> retryDelay * attemptNumber
            BackoffStrategy.EXPONENTIAL -> retryDelay * (1L shl (attemptNumber - 1))
        }
        return delay.coerceAtMost(maxRetryDelay)
    }
}

/**
 * 백오프 전략
 */
enum class BackoffStrategy {
    /**
     * 고정 지연 - 매 시도마다 동일한 지연 시간
     */
    FIXED,
    
    /**
     * 선형 증가 - 시도 횟수에 비례하여 지연 시간 증가
     */
    LINEAR,
    
    /**
     * 지수 증가 - 시도 횟수에 따라 지수적으로 지연 시간 증가
     */
    EXPONENTIAL
}