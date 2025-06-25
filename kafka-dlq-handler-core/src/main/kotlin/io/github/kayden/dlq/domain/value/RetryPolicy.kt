package io.github.kayden.dlq.domain.value

/**
 * 메시지 재처리 정책을 정의하는 값 객체
 * 
 * @property maxAttempts 최대 재시도 횟수
 * @property backoffStrategy 재시도 간격 계산 전략
 * @property retryableErrors 재시도 가능한 에러 타입 목록
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val backoffStrategy: BackoffStrategy = ExponentialBackoff(),
    val retryableErrors: Set<String> = DEFAULT_RETRYABLE_ERRORS
) {
    init {
        require(maxAttempts >= 0) { 
            "Max attempts must not be negative" 
        }
        require(maxAttempts <= MAX_ALLOWED_ATTEMPTS) { 
            "Max attempts cannot exceed $MAX_ALLOWED_ATTEMPTS" 
        }
    }
    
    companion object {
        const val MAX_ALLOWED_ATTEMPTS = 10
        
        val DEFAULT_RETRYABLE_ERRORS = setOf(
            "NetworkException",
            "TimeoutException",
            "TransientError",
            "TemporaryUnavailableException",
            "RetryableException"
        )
        
        /**
         * 기본 재시도 정책
         */
        val DEFAULT = RetryPolicy()
        
        /**
         * 재시도하지 않는 정책
         */
        val NO_RETRY = RetryPolicy(maxAttempts = 0)
        
        /**
         * 적극적인 재시도 정책
         */
        val AGGRESSIVE = RetryPolicy(
            maxAttempts = 5,
            backoffStrategy = FixedBackoff(java.time.Duration.ofSeconds(1))
        )
    }
    
    /**
     * 주어진 에러가 재시도 가능한지 확인한다.
     */
    fun isRetryable(errorClass: String?): Boolean {
        if (errorClass == null) return false
        return retryableErrors.any { errorClass.contains(it) }
    }
}