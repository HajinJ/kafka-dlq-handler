package io.github.kayden.dlq.core.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * DLQ 메시지 재처리 정책을 정의하는 data class.
 * 
 * 재시도 횟수, 백오프 전략, 타임아웃, 그리고 재시도 가능한 에러 타입 등
 * 재처리와 관련된 모든 정책을 포함한다.
 * 
 * @property maxRetries 최대 재시도 횟수
 * @property backoffStrategy 재시도 간격을 결정하는 백오프 전략
 * @property retryTimeout 개별 재시도의 타임아웃
 * @property maxRetryDuration 전체 재시도 기간의 최대값
 * @property retryableErrors 재시도 가능한 에러 타입 집합
 * @property nonRetryableErrors 재시도 불가능한 에러 타입 집합
 * @property retryOn 재시도 조건을 결정하는 커스텀 함수 (optional)
 * @property onRetryExhausted 재시도 소진 시 처리 방법
 * 
 * @since 0.1.0
 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val backoffStrategy: BackoffStrategy = BackoffStrategy.Exponential.DEFAULT,
    val retryTimeout: Duration? = null,
    val maxRetryDuration: Duration? = null,
    val retryableErrors: Set<ErrorType> = ErrorType.retryableTypes,
    val nonRetryableErrors: Set<ErrorType> = ErrorType.permanentTypes,
    val retryOn: ((DLQRecord) -> Boolean)? = null,
    val onRetryExhausted: RetryExhaustedAction = RetryExhaustedAction.MOVE_TO_FAILED
) {
    init {
        require(maxRetries >= 0) { "Max retries must not be negative" }
        retryTimeout?.let {
            require(it > Duration.ZERO) { "Retry timeout must be positive" }
        }
        maxRetryDuration?.let {
            require(it > Duration.ZERO) { "Max retry duration must be positive" }
        }
        require(retryableErrors.intersect(nonRetryableErrors).isEmpty()) {
            "Retryable and non-retryable errors must be mutually exclusive"
        }
    }
    
    /**
     * 주어진 레코드가 재시도 가능한지 판단한다.
     * 
     * @param record DLQ 레코드
     * @param currentTime 현재 시간 (epoch milliseconds)
     * @return 재시도 가능하면 true
     */
    fun canRetry(record: DLQRecord, currentTime: Long = System.currentTimeMillis()): Boolean {
        // 최대 재시도 횟수 확인
        if (record.retryCount >= maxRetries) {
            return false
        }
        
        // 에러 타입 확인
        if (record.errorType in nonRetryableErrors) {
            return false
        }
        
        if (retryableErrors.isNotEmpty() && record.errorType !in retryableErrors) {
            return false
        }
        
        // 최대 재시도 기간 확인
        maxRetryDuration?.let { maxDuration ->
            val elapsedTime = currentTime - record.createdAt
            if (elapsedTime > maxDuration.inWholeMilliseconds) {
                return false
            }
        }
        
        // 커스텀 재시도 조건 확인
        retryOn?.let { condition ->
            if (!condition(record)) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * 다음 재시도까지의 지연 시간을 계산한다.
     * 
     * @param attemptNumber 시도 번호 (1부터 시작)
     * @return 지연 시간
     */
    fun calculateDelay(attemptNumber: Int): Duration {
        return backoffStrategy.calculateDelay(attemptNumber)
    }
    
    /**
     * 재시도 정책을 업데이트한 새로운 인스턴스를 생성한다.
     * 
     * @param maxRetries 새로운 최대 재시도 횟수
     * @return 업데이트된 재시도 정책
     */
    fun withMaxRetries(maxRetries: Int): RetryPolicy =
        copy(maxRetries = maxRetries)
    
    /**
     * 백오프 전략을 업데이트한 새로운 인스턴스를 생성한다.
     * 
     * @param strategy 새로운 백오프 전략
     * @return 업데이트된 재시도 정책
     */
    fun withBackoffStrategy(strategy: BackoffStrategy): RetryPolicy =
        copy(backoffStrategy = strategy)
    
    /**
     * 재시도 가능한 에러를 추가한 새로운 인스턴스를 생성한다.
     * 
     * @param errorTypes 추가할 에러 타입들
     * @return 업데이트된 재시도 정책
     */
    fun withRetryableErrors(vararg errorTypes: ErrorType): RetryPolicy =
        copy(retryableErrors = retryableErrors + errorTypes.toSet())
    
    /**
     * 재시도 불가능한 에러를 추가한 새로운 인스턴스를 생성한다.
     * 
     * @param errorTypes 추가할 에러 타입들
     * @return 업데이트된 재시도 정책
     */
    fun withNonRetryableErrors(vararg errorTypes: ErrorType): RetryPolicy =
        copy(nonRetryableErrors = nonRetryableErrors + errorTypes.toSet())
    
    companion object {
        /**
         * 재시도 없음 정책.
         */
        val NO_RETRY = RetryPolicy(maxRetries = 0)
        
        /**
         * 기본 재시도 정책.
         * 3회 재시도, 지수 백오프, 일시적 에러만 재시도.
         */
        val DEFAULT = RetryPolicy(
            maxRetries = 3,
            backoffStrategy = BackoffStrategy.Exponential.DEFAULT,
            retryableErrors = ErrorType.transientTypes
        )
        
        /**
         * 공격적인 재시도 정책.
         * 5회 재시도, 빠른 지수 백오프, 대부분의 에러 재시도.
         */
        val AGGRESSIVE = RetryPolicy(
            maxRetries = 5,
            backoffStrategy = BackoffStrategy.Exponential.FAST,
            retryableErrors = ErrorType.retryableTypes,
            maxRetryDuration = 1.hours
        )
        
        /**
         * 보수적인 재시도 정책.
         * 2회 재시도, 고정 백오프, 네트워크 에러만 재시도.
         */
        val CONSERVATIVE = RetryPolicy(
            maxRetries = 2,
            backoffStrategy = BackoffStrategy.Fixed.TEN_SECONDS,
            retryableErrors = setOf(ErrorType.TRANSIENT_NETWORK_ERROR),
            maxRetryDuration = 5.minutes
        )
        
        /**
         * 장기 재시도 정책.
         * 10회 재시도, 선형 백오프, 최대 1일.
         */
        val LONG_TERM = RetryPolicy(
            maxRetries = 10,
            backoffStrategy = BackoffStrategy.Linear(
                initialDelay = 1.minutes,
                increment = 5.minutes,
                maxDelay = 1.hours
            ),
            maxRetryDuration = 1.days,
            onRetryExhausted = RetryExhaustedAction.MOVE_TO_ARCHIVE
        )
        
        /**
         * 에러 타입별 재시도 정책을 생성한다.
         * 
         * @param errorType 에러 타입
         * @return 에러 타입에 적합한 재시도 정책
         */
        fun forErrorType(errorType: ErrorType): RetryPolicy {
            return when (errorType) {
                ErrorType.TRANSIENT_NETWORK_ERROR -> DEFAULT.copy(
                    maxRetries = 5,
                    backoffStrategy = BackoffStrategy.ExponentialWithJitter.DEFAULT
                )
                ErrorType.TRANSIENT_SERVICE_ERROR -> DEFAULT.copy(
                    maxRetries = 3,
                    backoffStrategy = BackoffStrategy.ExponentialWithJitter.FULL_JITTER
                )
                ErrorType.RESOURCE_EXHAUSTED -> DEFAULT.copy(
                    maxRetries = 10,
                    backoffStrategy = BackoffStrategy.Linear.DEFAULT,
                    maxRetryDuration = 1.hours
                )
                ErrorType.AUTHENTICATION_ERROR -> DEFAULT.copy(
                    maxRetries = 1,
                    backoffStrategy = BackoffStrategy.Fixed.ONE_MINUTE
                )
                else -> NO_RETRY
            }
        }
    }
}

/**
 * 재시도 소진 시 수행할 작업을 정의하는 열거형.
 */
enum class RetryExhaustedAction {
    /**
     * FAILED 상태로 이동.
     */
    MOVE_TO_FAILED,
    
    /**
     * EXPIRED 상태로 이동.
     */
    MOVE_TO_EXPIRED,
    
    /**
     * 아카이브로 이동 (장기 보관).
     */
    MOVE_TO_ARCHIVE,
    
    /**
     * 삭제.
     */
    DELETE,
    
    /**
     * 알림 발송 후 ON_HOLD 상태로 이동.
     */
    ALERT_AND_HOLD,
    
    /**
     * 커스텀 핸들러로 처리.
     */
    CUSTOM
}