package io.github.kayden.dlq.core.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes

class BackoffStrategyTest {
    
    @Test
    @DisplayName("None 전략은 항상 0 지연을 반환한다")
    fun shouldReturnZeroDelayForNoneStrategy() {
        val strategy = BackoffStrategy.None
        
        assertEquals(Duration.ZERO, strategy.calculateDelay(1))
        assertEquals(Duration.ZERO, strategy.calculateDelay(5))
        assertEquals(Duration.ZERO, strategy.calculateDelay(100))
    }
    
    @Test
    @DisplayName("Fixed 전략은 항상 동일한 지연을 반환한다")
    fun shouldReturnConstantDelayForFixedStrategy() {
        val delay = 5.seconds
        val strategy = BackoffStrategy.Fixed(delay)
        
        assertEquals(delay, strategy.calculateDelay(1))
        assertEquals(delay, strategy.calculateDelay(5))
        assertEquals(delay, strategy.calculateDelay(100))
    }
    
    @Test
    @DisplayName("Fixed 전략은 음수 지연으로 생성할 수 없다")
    fun shouldThrowExceptionForNegativeFixedDelay() {
        assertThrows<IllegalArgumentException> {
            BackoffStrategy.Fixed((-1).seconds)
        }.also { exception ->
            assertEquals("Delay must not be negative", exception.message)
        }
    }
    
    @Test
    @DisplayName("Fixed 전략의 사전 정의된 상수들이 올바르게 동작한다")
    fun shouldHaveCorrectPredefinedFixedStrategies() {
        assertEquals(1.seconds, BackoffStrategy.Fixed.ONE_SECOND.calculateDelay(1))
        assertEquals(5.seconds, BackoffStrategy.Fixed.FIVE_SECONDS.calculateDelay(1))
        assertEquals(10.seconds, BackoffStrategy.Fixed.TEN_SECONDS.calculateDelay(1))
        assertEquals(30.seconds, BackoffStrategy.Fixed.THIRTY_SECONDS.calculateDelay(1))
        assertEquals(60.seconds, BackoffStrategy.Fixed.ONE_MINUTE.calculateDelay(1))
    }
    
    @ParameterizedTest
    @CsvSource(
        "1, 1000",
        "2, 2000",
        "3, 3000",
        "5, 5000",
        "10, 10000"
    )
    @DisplayName("Linear 전략은 선형적으로 증가하는 지연을 반환한다")
    fun shouldReturnLinearlyIncreasingDelay(attemptNumber: Int, expectedMillis: Long) {
        val strategy = BackoffStrategy.Linear(
            initialDelay = 1.seconds,
            increment = 1.seconds
        )
        
        assertEquals(expectedMillis.milliseconds, strategy.calculateDelay(attemptNumber))
    }
    
    @Test
    @DisplayName("Linear 전략은 최대 지연을 초과하지 않는다")
    fun shouldRespectMaxDelayInLinearStrategy() {
        val strategy = BackoffStrategy.Linear(
            initialDelay = 1.seconds,
            increment = 2.seconds,
            maxDelay = 5.seconds
        )
        
        assertEquals(1.seconds, strategy.calculateDelay(1))
        assertEquals(3.seconds, strategy.calculateDelay(2))
        assertEquals(5.seconds, strategy.calculateDelay(3))
        assertEquals(5.seconds, strategy.calculateDelay(10)) // Capped at max
    }
    
    @Test
    @DisplayName("Linear 전략은 유효하지 않은 파라미터로 생성할 수 없다")
    fun shouldValidateLinearStrategyParameters() {
        assertThrows<IllegalArgumentException> {
            BackoffStrategy.Linear((-1).seconds, 1.seconds)
        }
        
        assertThrows<IllegalArgumentException> {
            BackoffStrategy.Linear(1.seconds, (-1).seconds)
        }
        
        assertThrows<IllegalArgumentException> {
            BackoffStrategy.Linear(5.seconds, 1.seconds, maxDelay = 2.seconds)
        }
    }
    
    @ParameterizedTest
    @CsvSource(
        "1, 1000",
        "2, 2000",
        "3, 4000",
        "4, 8000",
        "5, 16000"
    )
    @DisplayName("Exponential 전략은 지수적으로 증가하는 지연을 반환한다")
    fun shouldReturnExponentiallyIncreasingDelay(attemptNumber: Int, expectedMillis: Long) {
        val strategy = BackoffStrategy.Exponential(
            baseDelay = 1.seconds,
            multiplier = 2.0
        )
        
        assertEquals(expectedMillis.milliseconds, strategy.calculateDelay(attemptNumber))
    }
    
    @Test
    @DisplayName("Exponential 전략은 최대 지연을 초과하지 않는다")
    fun shouldRespectMaxDelayInExponentialStrategy() {
        val strategy = BackoffStrategy.Exponential(
            baseDelay = 1.seconds,
            multiplier = 2.0,
            maxDelay = 10.seconds
        )
        
        assertEquals(1.seconds, strategy.calculateDelay(1))
        assertEquals(2.seconds, strategy.calculateDelay(2))
        assertEquals(4.seconds, strategy.calculateDelay(3))
        assertEquals(8.seconds, strategy.calculateDelay(4))
        assertEquals(10.seconds, strategy.calculateDelay(5)) // Capped at max
        assertEquals(10.seconds, strategy.calculateDelay(10)) // Still capped
    }
    
    @Test
    @DisplayName("Exponential 전략은 유효하지 않은 파라미터로 생성할 수 없다")
    fun shouldValidateExponentialStrategyParameters() {
        assertThrows<IllegalArgumentException> {
            BackoffStrategy.Exponential((-1).seconds)
        }
        
        assertThrows<IllegalArgumentException> {
            BackoffStrategy.Exponential(1.seconds, multiplier = 0.5)
        }
        
        assertThrows<IllegalArgumentException> {
            BackoffStrategy.Exponential(1.seconds, multiplier = 1.0)
        }
        
        assertThrows<IllegalArgumentException> {
            BackoffStrategy.Exponential(5.seconds, maxDelay = 2.seconds)
        }
    }
    
    @Test
    @DisplayName("ExponentialWithJitter는 기본 지연에 변동성을 추가한다")
    fun shouldAddJitterToExponentialDelay() {
        val baseStrategy = BackoffStrategy.Exponential(1.seconds)
        val strategy = BackoffStrategy.ExponentialWithJitter(
            baseStrategy = baseStrategy,
            jitterFactor = 0.1,
            fullJitter = false
        )
        
        val delays = (1..10).map { strategy.calculateDelay(2) }
        val baseDelay = baseStrategy.calculateDelay(2)
        
        // 모든 지연이 기본 지연의 ±10% 범위 내에 있어야 함
        delays.forEach { delay ->
            val ratio = delay.inWholeMilliseconds.toDouble() / baseDelay.inWholeMilliseconds
            assertTrue(ratio in 0.9..1.1, "Delay $delay should be within 10% of base delay $baseDelay")
        }
        
        // 적어도 일부는 다른 값이어야 함 (지터가 적용되었음을 확인)
        assertTrue(delays.distinct().size > 1, "Jitter should produce different delays")
    }
    
    @Test
    @DisplayName("Full jitter는 0과 기본 지연 사이의 값을 반환한다")
    fun shouldApplyFullJitter() {
        val baseStrategy = BackoffStrategy.Exponential(10.seconds)
        val strategy = BackoffStrategy.ExponentialWithJitter(
            baseStrategy = baseStrategy,
            jitterFactor = 1.0,
            fullJitter = true
        )
        
        val delays = (1..20).map { strategy.calculateDelay(1) }
        val baseDelay = baseStrategy.calculateDelay(1)
        
        delays.forEach { delay ->
            assertTrue(delay >= Duration.ZERO, "Delay should not be negative")
            assertTrue(delay <= baseDelay, "Delay should not exceed base delay")
        }
        
        // 다양한 값이 나와야 함
        assertTrue(delays.distinct().size > 10, "Full jitter should produce varied delays")
    }
    
    @Test
    @DisplayName("Custom 전략은 사용자 정의 함수를 사용한다")
    fun shouldUseCustomDelayFunction() {
        val strategy = BackoffStrategy.Custom("Square") { attempt ->
            (attempt * attempt).seconds
        }
        
        assertEquals(1.seconds, strategy.calculateDelay(1))
        assertEquals(4.seconds, strategy.calculateDelay(2))
        assertEquals(9.seconds, strategy.calculateDelay(3))
        assertEquals(16.seconds, strategy.calculateDelay(4))
    }
    
    @Test
    @DisplayName("Custom 전략은 음수 지연을 반환할 수 없다")
    fun shouldValidateCustomStrategyDelay() {
        val strategy = BackoffStrategy.Custom { _ -> (-1).seconds }
        
        assertThrows<IllegalArgumentException> {
            strategy.calculateDelay(1)
        }.also { exception ->
            assertEquals("Delay must not be negative", exception.message)
        }
    }
    
    @Test
    @DisplayName("모든 전략은 양수 시도 번호를 요구한다")
    fun shouldRequirePositiveAttemptNumber() {
        val strategies = listOf(
            BackoffStrategy.Linear.DEFAULT,
            BackoffStrategy.Exponential.DEFAULT,
            BackoffStrategy.ExponentialWithJitter.DEFAULT,
            BackoffStrategy.Custom { 1.seconds }
        )
        
        strategies.forEach { strategy ->
            assertThrows<IllegalArgumentException> {
                strategy.calculateDelay(0)
            }
            assertThrows<IllegalArgumentException> {
                strategy.calculateDelay(-1)
            }
        }
    }
    
    @Test
    @DisplayName("에러 타입별 권장 전략을 올바르게 반환한다")
    fun shouldReturnRecommendedStrategyForErrorType() {
        assertEquals(
            BackoffStrategy.Exponential.DEFAULT,
            BackoffStrategy.recommendedFor(ErrorType.TRANSIENT_NETWORK_ERROR)
        )
        
        assertEquals(
            BackoffStrategy.ExponentialWithJitter.DEFAULT,
            BackoffStrategy.recommendedFor(ErrorType.TRANSIENT_SERVICE_ERROR)
        )
        
        assertEquals(
            BackoffStrategy.Linear.DEFAULT,
            BackoffStrategy.recommendedFor(ErrorType.RESOURCE_EXHAUSTED)
        )
        
        assertEquals(
            BackoffStrategy.Fixed.THIRTY_SECONDS,
            BackoffStrategy.recommendedFor(ErrorType.AUTHENTICATION_ERROR)
        )
        
        assertEquals(
            BackoffStrategy.None,
            BackoffStrategy.recommendedFor(ErrorType.PERMANENT_FAILURE)
        )
    }
    
    @Test
    @DisplayName("피보나치 백오프 전략이 올바르게 동작한다")
    fun shouldCalculateFibonacciBackoff() {
        val strategy = BackoffStrategy.fibonacci(
            baseDelay = 1.seconds,
            maxDelay = 100.seconds
        )
        
        assertEquals(1.seconds, strategy.calculateDelay(1))  // 1
        assertEquals(1.seconds, strategy.calculateDelay(2))  // 1
        assertEquals(2.seconds, strategy.calculateDelay(3))  // 2
        assertEquals(3.seconds, strategy.calculateDelay(4))  // 3
        assertEquals(5.seconds, strategy.calculateDelay(5))  // 5
        assertEquals(8.seconds, strategy.calculateDelay(6))  // 8
        assertEquals(13.seconds, strategy.calculateDelay(7)) // 13
        
        // 최대값 제한 확인
        val largeAttempt = strategy.calculateDelay(20)
        assertTrue(largeAttempt <= 100.seconds, "Should be capped at max delay")
    }
    
    @Test
    @DisplayName("사전 정의된 전략들이 올바른 특성을 가진다")
    fun shouldHaveCorrectPredefinedStrategies() {
        // DEFAULT
        val defaultStrategy = BackoffStrategy.Exponential.DEFAULT
        assertEquals(1.seconds, defaultStrategy.baseDelay)
        assertEquals(2.0, defaultStrategy.multiplier)
        assertEquals(300.seconds, defaultStrategy.maxDelay)
        
        // FAST
        val fastStrategy = BackoffStrategy.Exponential.FAST
        assertEquals(100.milliseconds, fastStrategy.baseDelay)
        assertEquals(2.0, fastStrategy.multiplier)
        assertEquals(10.seconds, fastStrategy.maxDelay)
        
        // Linear DEFAULT
        val linearDefault = BackoffStrategy.Linear.DEFAULT
        assertEquals(1.seconds, linearDefault.initialDelay)
        assertEquals(1.seconds, linearDefault.increment)
        assertEquals(60.seconds, linearDefault.maxDelay)
    }
}