package io.github.kayden.dlq.core.model

import java.time.Duration
import java.time.Instant

/**
 * DLQ 메시지의 재처리 정책을 정의한다.
 *
 * 이 클래스는 재시도 횟수, 백오프 전략, 타임아웃 등
 * 재처리와 관련된 모든 설정을 포함한다.
 *
 * @property maxAttempts 최대 재시도 횟수
 * @property backoffStrategy 재시도 간격 계산 전략
 * @property retryableErrors 재시도 가능한 에러 타입 목록
 * @property timeout 각 재시도의 타임아웃 시간
 * @property retryUntil 재시도를 중단할 시간 (선택적)
 * @property onRetryExhausted 재시도가 모두 소진됐을 때의 동작
 *
 * @see BackoffStrategy
 * @see ErrorType
 * @since 0.1.0
 */
data class RetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val backoffStrategy: BackoffStrategy = DEFAULT_BACKOFF_STRATEGY,
    val retryableErrors: Set<ErrorType> = DEFAULT_RETRYABLE_ERRORS,
    val timeout: Duration = DEFAULT_TIMEOUT,
    val retryUntil: Instant? = null,
    val onRetryExhausted: RetryExhaustedAction = RetryExhaustedAction.MOVE_TO_FAILED
) {
    init {
        require(maxAttempts > 0) { 
            "Max attempts must be positive" 
        }
        require(timeout.toMillis() > 0) { 
            "Timeout must be positive" 
        }
    }
    
    /**
     * 주어진 에러 타입이 재시도 가능한지 확인한다.
     *
     * @param errorType 확인할 에러 타입
     * @return 재시도 가능 여부
     */
    fun isRetryable(errorType: ErrorType): Boolean = 
        errorType in retryableErrors
    
    /**
     * 현재 시간 기준으로 재시도가 가능한지 확인한다.
     *
     * @param now 현재 시간
     * @return 재시도 가능 여부
     */
    fun canRetryAt(now: Instant = Instant.now()): Boolean = 
        retryUntil?.let { now.isBefore(it) } ?: true
    
    /**
     * 재시도 횟수가 남아있는지 확인한다.
     *
     * @param currentAttempts 현재까지의 시도 횟수
     * @return 재시도 가능 여부
     */
    fun hasRetriesLeft(currentAttempts: Int): Boolean = 
        currentAttempts < maxAttempts
    
    /**
     * 재시도가 모두 소진됐을 때의 동작
     */
    enum class RetryExhaustedAction {
        /** DLQ 메시지를 FAILED 상태로 변경 */
        MOVE_TO_FAILED,
        
        /** DLQ 메시지를 EXPIRED 상태로 변경 */
        MOVE_TO_EXPIRED,
        
        /** DLQ 메시지를 SKIPPED 상태로 변경 */
        SKIP,
        
        /** 별도의 DLQ로 이동 (DLQ의 DLQ) */
        MOVE_TO_SECONDARY_DLQ,
        
        /** 메시지 삭제 */
        DELETE
    }
    
    companion object {
        /** 기본 최대 재시도 횟수 */
        const val DEFAULT_MAX_ATTEMPTS = 3
        
        /** 기본 타임아웃 시간 */
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)
        
        /** 기본 백오프 전략 */
        val DEFAULT_BACKOFF_STRATEGY = BackoffStrategy.ExponentialWithJitter(
            baseDelay = Duration.ofSeconds(1),
            multiplier = 2.0,
            maxDelay = Duration.ofMinutes(5)
        )
        
        /** 기본적으로 재시도 가능한 에러 타입 */
        val DEFAULT_RETRYABLE_ERRORS = setOf(
            ErrorType.TRANSIENT,
            ErrorType.TIMEOUT,
            ErrorType.RESOURCE_EXHAUSTED,
            ErrorType.INFRASTRUCTURE
        )
        
        /** 재시도하지 않는 정책 */
        val NO_RETRY = RetryPolicy(
            maxAttempts = 1,
            backoffStrategy = BackoffStrategy.NoRetry,
            retryableErrors = emptySet()
        )
        
        /** 적극적인 재시도 정책 */
        val AGGRESSIVE = RetryPolicy(
            maxAttempts = 10,
            backoffStrategy = BackoffStrategy.Fixed(
                delay = Duration.ofSeconds(1)
            ),
            timeout = Duration.ofSeconds(60)
        )
        
        /** 보수적인 재시도 정책 */
        val CONSERVATIVE = RetryPolicy(
            maxAttempts = 3,
            backoffStrategy = BackoffStrategy.ExponentialWithJitter(
                baseDelay = Duration.ofSeconds(5),
                multiplier = 3.0,
                maxDelay = Duration.ofMinutes(10)
            ),
            timeout = Duration.ofSeconds(30),
            retryableErrors = setOf(ErrorType.TRANSIENT, ErrorType.TIMEOUT)
        )
    }
}