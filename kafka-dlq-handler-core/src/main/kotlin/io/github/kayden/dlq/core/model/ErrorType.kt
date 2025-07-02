package io.github.kayden.dlq.core.model

/**
 * DLQ 메시지의 에러 타입을 분류하는 열거형.
 * 
 * 에러 타입은 재처리 전략을 결정하는 중요한 요소이며,
 * 일시적 에러와 영구적 에러를 구분하여 적절한 처리 방식을 선택한다.
 * 
 * @since 0.1.0
 */
enum class ErrorType {
    /**
     * 일시적 네트워크 에러.
     * 네트워크 연결 실패, 타임아웃 등의 일시적인 네트워크 문제.
     * 일반적으로 재시도로 해결 가능.
     */
    TRANSIENT_NETWORK_ERROR,
    
    /**
     * 일시적 서비스 에러.
     * 외부 서비스 일시적 장애, 과부하 상태 등.
     * 백오프 전략을 사용한 재시도로 해결 가능.
     */
    TRANSIENT_SERVICE_ERROR,
    
    /**
     * 영구적 실패.
     * 비즈니스 로직 위반, 데이터 무결성 오류 등.
     * 재시도해도 해결되지 않는 영구적인 문제.
     */
    PERMANENT_FAILURE,
    
    /**
     * 데이터 직렬화/역직렬화 에러.
     * 메시지 포맷 오류, 인코딩 문제 등.
     * 데이터 수정 없이는 해결 불가능.
     */
    SERIALIZATION_ERROR,
    
    /**
     * 인증/인가 에러.
     * 권한 부족, 인증 토큰 만료 등.
     * 자격 증명 갱신으로 해결 가능할 수 있음.
     */
    AUTHENTICATION_ERROR,
    
    /**
     * 유효성 검사 에러.
     * 입력 데이터가 비즈니스 규칙을 위반.
     * 데이터 수정이 필요한 영구적 에러.
     */
    VALIDATION_ERROR,
    
    /**
     * 리소스 부족 에러.
     * 메모리 부족, 디스크 공간 부족 등.
     * 리소스 확보 후 재시도 가능.
     */
    RESOURCE_EXHAUSTED,
    
    /**
     * 알 수 없는 에러.
     * 분류되지 않은 예외적인 에러.
     * 상황에 따라 재시도 전략 결정.
     */
    UNKNOWN;
    
    /**
     * 재시도 가능한 에러 타입인지 확인한다.
     * 
     * @return 재시도 가능한 타입이면 true
     */
    fun isRetryable(): Boolean = this in retryableTypes
    
    /**
     * 일시적 에러 타입인지 확인한다.
     * 
     * @return 일시적 에러 타입이면 true
     */
    fun isTransient(): Boolean = this in transientTypes
    
    /**
     * 영구적 에러 타입인지 확인한다.
     * 
     * @return 영구적 에러 타입이면 true
     */
    fun isPermanent(): Boolean = this in permanentTypes
    
    /**
     * 권장 백오프 전략을 반환한다.
     * 
     * @return 에러 타입에 따른 권장 백오프 전략
     */
    fun recommendedBackoffStrategy(): BackoffStrategy {
        return when (this) {
            TRANSIENT_NETWORK_ERROR -> BackoffStrategy.EXPONENTIAL
            TRANSIENT_SERVICE_ERROR -> BackoffStrategy.EXPONENTIAL_WITH_JITTER
            RESOURCE_EXHAUSTED -> BackoffStrategy.LINEAR
            AUTHENTICATION_ERROR -> BackoffStrategy.FIXED
            else -> BackoffStrategy.NONE
        }
    }
    
    companion object {
        /**
         * 재시도 가능한 에러 타입들.
         */
        val retryableTypes = setOf(
            TRANSIENT_NETWORK_ERROR,
            TRANSIENT_SERVICE_ERROR,
            RESOURCE_EXHAUSTED,
            AUTHENTICATION_ERROR,
            UNKNOWN
        )
        
        /**
         * 일시적 에러 타입들.
         */
        val transientTypes = setOf(
            TRANSIENT_NETWORK_ERROR,
            TRANSIENT_SERVICE_ERROR,
            RESOURCE_EXHAUSTED
        )
        
        /**
         * 영구적 에러 타입들.
         */
        val permanentTypes = setOf(
            PERMANENT_FAILURE,
            SERIALIZATION_ERROR,
            VALIDATION_ERROR
        )
        
        /**
         * 예외 클래스명을 기반으로 에러 타입을 추론한다.
         * 
         * @param exceptionClassName 예외 클래스명
         * @return 추론된 에러 타입
         */
        fun fromException(exceptionClassName: String?): ErrorType {
            return when {
                exceptionClassName == null -> UNKNOWN
                exceptionClassName.contains("Network", ignoreCase = true) ||
                exceptionClassName.contains("Timeout", ignoreCase = true) ||
                exceptionClassName.contains("Connection", ignoreCase = true) -> TRANSIENT_NETWORK_ERROR
                exceptionClassName.contains("Service", ignoreCase = true) ||
                exceptionClassName.contains("Unavailable", ignoreCase = true) -> TRANSIENT_SERVICE_ERROR
                exceptionClassName.contains("Serialization", ignoreCase = true) ||
                exceptionClassName.contains("Deserialization", ignoreCase = true) ||
                exceptionClassName.contains("Json", ignoreCase = true) -> SERIALIZATION_ERROR
                exceptionClassName.contains("Auth", ignoreCase = true) ||
                exceptionClassName.contains("Permission", ignoreCase = true) ||
                exceptionClassName.contains("Forbidden", ignoreCase = true) -> AUTHENTICATION_ERROR
                exceptionClassName.contains("Validation", ignoreCase = true) ||
                exceptionClassName.contains("Invalid", ignoreCase = true) -> VALIDATION_ERROR
                exceptionClassName.contains("Memory", ignoreCase = true) ||
                exceptionClassName.contains("Resource", ignoreCase = true) -> RESOURCE_EXHAUSTED
                else -> UNKNOWN
            }
        }
    }
}

/**
 * 백오프 전략을 정의하는 열거형.
 */
enum class BackoffStrategy {
    /**
     * 백오프 없음. 즉시 재시도.
     */
    NONE,
    
    /**
     * 고정 지연. 항상 동일한 시간 대기.
     */
    FIXED,
    
    /**
     * 선형 증가. 재시도마다 일정하게 지연 시간 증가.
     */
    LINEAR,
    
    /**
     * 지수 증가. 재시도마다 지연 시간을 2배씩 증가.
     */
    EXPONENTIAL,
    
    /**
     * 지터를 포함한 지수 증가. 
     * 여러 인스턴스의 동시 재시도를 방지하기 위한 무작위 요소 포함.
     */
    EXPONENTIAL_WITH_JITTER
}