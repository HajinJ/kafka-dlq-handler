package io.github.kayden.dlq.core.model

/**
 * DLQ 메시지의 에러 타입 분류
 * 
 * 에러의 특성에 따라 재처리 전략을 결정하기 위한 분류 체계.
 * 각 타입은 서로 다른 재처리 정책과 백오프 전략을 적용받을 수 있음.
 * 
 * @since 0.1.0
 */
enum class ErrorType(
    val retryable: Boolean,
    val defaultMaxRetries: Int,
    val description: String
) {
    /**
     * 일시적 에러
     * 
     * 네트워크 장애, 일시적 서비스 중단 등 시간이 지나면 해결될 가능성이 높은 에러.
     * 재처리를 통해 성공할 가능성이 높음.
     */
    TRANSIENT(
        retryable = true,
        defaultMaxRetries = 5,
        description = "Temporary error that may be resolved by retrying"
    ),
    
    /**
     * 영구적 에러
     * 
     * 데이터 형식 오류, 권한 부족 등 재처리해도 해결되지 않는 에러.
     * 재처리가 무의미하며 수동 개입이 필요함.
     */
    PERMANENT(
        retryable = false,
        defaultMaxRetries = 0,
        description = "Permanent error that cannot be resolved by retrying"
    ),
    
    /**
     * 타임아웃 에러
     * 
     * 처리 시간 초과로 인한 에러.
     * 부하 상황에 따라 재처리 시 성공할 가능성 있음.
     */
    TIMEOUT(
        retryable = true,
        defaultMaxRetries = 3,
        description = "Operation timed out"
    ),
    
    /**
     * 직렬화/역직렬화 에러
     * 
     * 메시지 포맷이나 스키마 불일치로 인한 에러.
     * 대부분 영구적이나, 스키마 업데이트 후 재처리 가능할 수 있음.
     */
    SERIALIZATION(
        retryable = false,
        defaultMaxRetries = 1,
        description = "Message serialization/deserialization error"
    ),
    
    /**
     * 비즈니스 로직 에러
     * 
     * 애플리케이션 비즈니스 규칙 위반으로 인한 에러.
     * 데이터 수정이나 규칙 변경 후 재처리 가능.
     */
    BUSINESS_LOGIC(
        retryable = false,
        defaultMaxRetries = 0,
        description = "Business rule validation error"
    ),
    
    /**
     * 인프라 에러
     * 
     * 데이터베이스 연결 실패, 외부 서비스 장애 등 인프라 관련 에러.
     * 시간이 지나면 해결될 가능성이 높음.
     */
    INFRASTRUCTURE(
        retryable = true,
        defaultMaxRetries = 4,
        description = "Infrastructure-related error"
    ),
    
    /**
     * 리소스 부족 에러
     * 
     * 메모리 부족, 디스크 공간 부족 등 리소스 관련 에러.
     * 리소스 상황 개선 시 재처리 가능.
     */
    RESOURCE_EXHAUSTED(
        retryable = true,
        defaultMaxRetries = 3,
        description = "Resource exhaustion error"
    ),
    
    /**
     * 알 수 없는 에러
     * 
     * 분류되지 않은 예외적인 에러.
     * 기본적으로 재처리를 시도하되 제한적으로 수행.
     */
    UNKNOWN(
        retryable = true,
        defaultMaxRetries = 2,
        description = "Unknown error type"
    );
    
    companion object {
        /**
         * 예외 클래스를 기반으로 ErrorType 추론
         */
        fun fromException(exception: Throwable): ErrorType = when (exception) {
            is java.io.IOException,
            is java.net.SocketException,
            is java.net.UnknownHostException -> TRANSIENT
            
            is java.util.concurrent.TimeoutException -> TIMEOUT
            
            is IllegalArgumentException,
            is IllegalStateException,
            is NullPointerException -> PERMANENT
            
            is OutOfMemoryError,
            is StackOverflowError -> RESOURCE_EXHAUSTED
            
            else -> {
                // 클래스 이름으로 추가 확인 (동적 의존성 처리)
                when (exception.javaClass.simpleName) {
                    "TimeoutCancellationException" -> TIMEOUT
                    else -> UNKNOWN
                }
            }
        }
        
        /**
         * 에러 메시지를 기반으로 ErrorType 추론
         */
        fun fromErrorMessage(message: String?): ErrorType {
            if (message == null) return UNKNOWN
            
            return when {
                message.contains("timeout", ignoreCase = true) -> TIMEOUT
                message.contains("connection", ignoreCase = true) -> INFRASTRUCTURE
                message.contains("serialization", ignoreCase = true) -> SERIALIZATION
                message.contains("serialize", ignoreCase = true) -> SERIALIZATION
                message.contains("deserialize", ignoreCase = true) -> SERIALIZATION
                message.contains("memory", ignoreCase = true) -> RESOURCE_EXHAUSTED
                message.contains("validation", ignoreCase = true) -> BUSINESS_LOGIC
                else -> UNKNOWN
            }
        }
    }
}