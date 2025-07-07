package io.github.kayden.dlq.core.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days

class RetryPolicyTest {
    
    @Test
    @DisplayName("유효한 파라미터로 RetryPolicy를 생성할 수 있다")
    fun shouldCreateRetryPolicyWithValidParameters() {
        val policy = RetryPolicy(
            maxRetries = 5,
            backoffStrategy = BackoffStrategy.Exponential.DEFAULT,
            retryTimeout = 30.seconds,
            maxRetryDuration = 1.hours
        )
        
        assertEquals(5, policy.maxRetries)
        assertEquals(BackoffStrategy.Exponential.DEFAULT, policy.backoffStrategy)
        assertEquals(30.seconds, policy.retryTimeout)
        assertEquals(1.hours, policy.maxRetryDuration)
    }
    
    @Test
    @DisplayName("음수 최대 재시도로 생성할 수 없다")
    fun shouldThrowExceptionForNegativeMaxRetries() {
        assertThrows<IllegalArgumentException> {
            RetryPolicy(maxRetries = -1)
        }.also { exception ->
            assertEquals("Max retries must not be negative", exception.message)
        }
    }
    
    @Test
    @DisplayName("0 또는 음수 타임아웃으로 생성할 수 없다")
    fun shouldThrowExceptionForInvalidTimeouts() {
        assertThrows<IllegalArgumentException> {
            RetryPolicy(retryTimeout = 0.seconds)
        }
        
        assertThrows<IllegalArgumentException> {
            RetryPolicy(retryTimeout = (-1).seconds)
        }
        
        assertThrows<IllegalArgumentException> {
            RetryPolicy(maxRetryDuration = 0.seconds)
        }
        
        assertThrows<IllegalArgumentException> {
            RetryPolicy(maxRetryDuration = (-1).seconds)
        }
    }
    
    @Test
    @DisplayName("재시도 가능과 불가능 에러가 중복될 수 없다")
    fun shouldThrowExceptionForOverlappingErrorTypes() {
        assertThrows<IllegalArgumentException> {
            RetryPolicy(
                retryableErrors = setOf(ErrorType.TRANSIENT_NETWORK_ERROR),
                nonRetryableErrors = setOf(ErrorType.TRANSIENT_NETWORK_ERROR)
            )
        }.also { exception ->
            assertEquals("Retryable and non-retryable errors must be mutually exclusive", exception.message)
        }
    }
    
    @ParameterizedTest
    @CsvSource(
        "0, 3, true",
        "1, 3, true",
        "2, 3, true",
        "3, 3, false",
        "4, 3, false",
        "0, 0, false"
    )
    @DisplayName("재시도 가능 여부를 재시도 횟수로 판단한다")
    fun shouldDetermineRetryEligibilityByCount(retryCount: Int, maxRetries: Int, expected: Boolean) {
        val policy = RetryPolicy(maxRetries = maxRetries)
        val record = createTestRecord(retryCount = retryCount)
        
        assertEquals(expected, policy.canRetry(record))
    }
    
    @Test
    @DisplayName("재시도 불가능한 에러 타입은 재시도하지 않는다")
    fun shouldNotRetryNonRetryableErrors() {
        val policy = RetryPolicy(
            maxRetries = 10,
            nonRetryableErrors = setOf(ErrorType.PERMANENT_FAILURE)
        )
        val record = createTestRecord(
            retryCount = 0,
            errorType = ErrorType.PERMANENT_FAILURE
        )
        
        assertFalse(policy.canRetry(record))
    }
    
    @Test
    @DisplayName("재시도 가능한 에러 타입만 재시도한다")
    fun shouldOnlyRetryRetryableErrors() {
        val policy = RetryPolicy(
            maxRetries = 10,
            retryableErrors = setOf(ErrorType.TRANSIENT_NETWORK_ERROR)
        )
        
        val retryableRecord = createTestRecord(
            retryCount = 0,
            errorType = ErrorType.TRANSIENT_NETWORK_ERROR
        )
        assertTrue(policy.canRetry(retryableRecord))
        
        val nonRetryableRecord = createTestRecord(
            retryCount = 0,
            errorType = ErrorType.TRANSIENT_SERVICE_ERROR
        )
        assertFalse(policy.canRetry(nonRetryableRecord))
    }
    
    @Test
    @DisplayName("최대 재시도 기간을 초과하면 재시도하지 않는다")
    fun shouldNotRetryAfterMaxDuration() {
        val policy = RetryPolicy(
            maxRetries = 10,
            maxRetryDuration = 1.hours
        )
        
        val currentTime = System.currentTimeMillis()
        val oldRecord = createTestRecord(
            retryCount = 0,
            createdAt = currentTime - 2.hours.inWholeMilliseconds
        )
        
        assertFalse(policy.canRetry(oldRecord, currentTime))
    }
    
    @Test
    @DisplayName("커스텀 재시도 조건을 적용한다")
    fun shouldApplyCustomRetryCondition() {
        val policy = RetryPolicy(
            maxRetries = 10,
            retryOn = { record -> 
                record.payload.size < 1000 // 1KB 미만만 재시도
            }
        )
        
        val smallRecord = createTestRecord(
            retryCount = 0,
            payload = ByteArray(500)
        )
        assertTrue(policy.canRetry(smallRecord))
        
        val largeRecord = createTestRecord(
            retryCount = 0,
            payload = ByteArray(2000)
        )
        assertFalse(policy.canRetry(largeRecord))
    }
    
    @Test
    @DisplayName("지연 시간을 백오프 전략에 따라 계산한다")
    fun shouldCalculateDelayUsingBackoffStrategy() {
        val backoffStrategy = BackoffStrategy.Fixed(5.seconds)
        val policy = RetryPolicy(backoffStrategy = backoffStrategy)
        
        assertEquals(5.seconds, policy.calculateDelay(1))
        assertEquals(5.seconds, policy.calculateDelay(5))
    }
    
    @Test
    @DisplayName("정책을 업데이트한 새 인스턴스를 생성한다")
    fun shouldCreateUpdatedInstances() {
        val original = RetryPolicy(maxRetries = 3)
        
        val withMaxRetries = original.withMaxRetries(5)
        assertEquals(5, withMaxRetries.maxRetries)
        assertEquals(3, original.maxRetries)
        
        val withBackoff = original.withBackoffStrategy(BackoffStrategy.None)
        assertEquals(BackoffStrategy.None, withBackoff.backoffStrategy)
        
        val withRetryable = original.withRetryableErrors(ErrorType.UNKNOWN)
        assertTrue(ErrorType.UNKNOWN in withRetryable.retryableErrors)
        
        val withNonRetryable = original.withNonRetryableErrors(ErrorType.UNKNOWN)
        assertTrue(ErrorType.UNKNOWN in withNonRetryable.nonRetryableErrors)
    }
    
    @Test
    @DisplayName("사전 정의된 정책들이 올바른 특성을 가진다")
    fun shouldHaveCorrectPredefinedPolicies() {
        // NO_RETRY
        assertEquals(0, RetryPolicy.NO_RETRY.maxRetries)
        
        // DEFAULT
        assertEquals(3, RetryPolicy.DEFAULT.maxRetries)
        assertEquals(BackoffStrategy.Exponential.DEFAULT, RetryPolicy.DEFAULT.backoffStrategy)
        assertEquals(ErrorType.transientTypes, RetryPolicy.DEFAULT.retryableErrors)
        
        // AGGRESSIVE
        assertEquals(5, RetryPolicy.AGGRESSIVE.maxRetries)
        assertEquals(BackoffStrategy.Exponential.FAST, RetryPolicy.AGGRESSIVE.backoffStrategy)
        assertEquals(1.hours, RetryPolicy.AGGRESSIVE.maxRetryDuration)
        
        // CONSERVATIVE
        assertEquals(2, RetryPolicy.CONSERVATIVE.maxRetries)
        assertEquals(BackoffStrategy.Fixed.TEN_SECONDS, RetryPolicy.CONSERVATIVE.backoffStrategy)
        assertEquals(setOf(ErrorType.TRANSIENT_NETWORK_ERROR), RetryPolicy.CONSERVATIVE.retryableErrors)
        
        // LONG_TERM
        assertEquals(10, RetryPolicy.LONG_TERM.maxRetries)
        assertTrue(RetryPolicy.LONG_TERM.backoffStrategy is BackoffStrategy.Linear)
        assertEquals(1.days, RetryPolicy.LONG_TERM.maxRetryDuration)
        assertEquals(RetryExhaustedAction.MOVE_TO_ARCHIVE, RetryPolicy.LONG_TERM.onRetryExhausted)
    }
    
    @Test
    @DisplayName("에러 타입별 정책을 올바르게 생성한다")
    fun shouldCreatePolicyForErrorType() {
        val networkPolicy = RetryPolicy.forErrorType(ErrorType.TRANSIENT_NETWORK_ERROR)
        assertEquals(5, networkPolicy.maxRetries)
        assertTrue(networkPolicy.backoffStrategy is BackoffStrategy.ExponentialWithJitter)
        
        val servicePolicy = RetryPolicy.forErrorType(ErrorType.TRANSIENT_SERVICE_ERROR)
        assertEquals(3, servicePolicy.maxRetries)
        
        val resourcePolicy = RetryPolicy.forErrorType(ErrorType.RESOURCE_EXHAUSTED)
        assertEquals(10, resourcePolicy.maxRetries)
        assertTrue(resourcePolicy.backoffStrategy is BackoffStrategy.Linear)
        
        val authPolicy = RetryPolicy.forErrorType(ErrorType.AUTHENTICATION_ERROR)
        assertEquals(1, authPolicy.maxRetries)
        
        val permanentPolicy = RetryPolicy.forErrorType(ErrorType.PERMANENT_FAILURE)
        assertEquals(0, permanentPolicy.maxRetries)
    }
    
    @Test
    @DisplayName("복잡한 재시도 시나리오를 올바르게 처리한다")
    fun shouldHandleComplexRetryScenarios() {
        val policy = RetryPolicy(
            maxRetries = 5,
            backoffStrategy = BackoffStrategy.Exponential.DEFAULT,
            maxRetryDuration = 30.minutes,
            retryableErrors = setOf(
                ErrorType.TRANSIENT_NETWORK_ERROR,
                ErrorType.TRANSIENT_SERVICE_ERROR
            ),
            retryOn = { record ->
                record.originalTopic != "sensitive-topic"
            }
        )
        
        val currentTime = System.currentTimeMillis()
        
        // 시나리오 1: 모든 조건 충족
        val validRecord = createTestRecord(
            retryCount = 2,
            errorType = ErrorType.TRANSIENT_NETWORK_ERROR,
            createdAt = currentTime - 10.minutes.inWholeMilliseconds,
            originalTopic = "regular-topic"
        )
        assertTrue(policy.canRetry(validRecord, currentTime))
        
        // 시나리오 2: 최대 재시도 초과
        val maxRetriesRecord = validRecord.copy(retryCount = 5)
        assertFalse(policy.canRetry(maxRetriesRecord, currentTime))
        
        // 시나리오 3: 시간 초과
        val timeExpiredRecord = validRecord.copy(
            createdAt = currentTime - 40.minutes.inWholeMilliseconds
        )
        assertFalse(policy.canRetry(timeExpiredRecord, currentTime))
        
        // 시나리오 4: 재시도 불가능한 에러
        val wrongErrorRecord = validRecord.copy(
            errorType = ErrorType.PERMANENT_FAILURE
        )
        assertFalse(policy.canRetry(wrongErrorRecord, currentTime))
        
        // 시나리오 5: 커스텀 조건 실패
        val sensitiveRecord = validRecord.copy(
            originalTopic = "sensitive-topic"
        )
        assertFalse(policy.canRetry(sensitiveRecord, currentTime))
    }
    
    private fun createTestRecord(
        retryCount: Int = 0,
        errorType: ErrorType = ErrorType.TRANSIENT_NETWORK_ERROR,
        createdAt: Long = System.currentTimeMillis(),
        payload: ByteArray = "test".toByteArray(),
        originalTopic: String = "test-topic"
    ): DLQRecord {
        return DLQRecord(
            messageKey = "test-key",
            originalTopic = originalTopic,
            originalPartition = 0,
            originalOffset = 123L,
            payload = payload,
            headers = emptyMap(),
            errorClass = "TestException",
            errorMessage = "Test error",
            errorType = errorType,
            stackTrace = null,
            retryCount = retryCount,
            createdAt = createdAt
        )
    }
}