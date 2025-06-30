package io.github.kayden.dlq.core.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.time.Instant

@DisplayName("RetryPolicy 테스트")
class RetryPolicyTest {
    
    @Test
    fun `기본 설정으로 생성되어야 한다`() {
        // Given & When
        val policy = RetryPolicy()
        
        // Then
        assertThat(policy.maxAttempts).isEqualTo(RetryPolicy.DEFAULT_MAX_ATTEMPTS)
        assertThat(policy.backoffStrategy).isEqualTo(RetryPolicy.DEFAULT_BACKOFF_STRATEGY)
        assertThat(policy.retryableErrors).isEqualTo(RetryPolicy.DEFAULT_RETRYABLE_ERRORS)
        assertThat(policy.timeout).isEqualTo(RetryPolicy.DEFAULT_TIMEOUT)
        assertThat(policy.retryUntil).isNull()
        assertThat(policy.onRetryExhausted).isEqualTo(
            RetryPolicy.RetryExhaustedAction.MOVE_TO_FAILED
        )
    }
    
    @Nested
    @DisplayName("유효성 검증")
    inner class ValidationTest {
        
        @Test
        fun `maxAttempts는 양수여야 한다`() {
            assertThatThrownBy {
                RetryPolicy(maxAttempts = 0)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Max attempts must be positive")
            
            assertThatThrownBy {
                RetryPolicy(maxAttempts = -1)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Max attempts must be positive")
        }
        
        @Test
        fun `timeout은 양수여야 한다`() {
            assertThatThrownBy {
                RetryPolicy(timeout = Duration.ZERO)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Timeout must be positive")
            
            assertThatThrownBy {
                RetryPolicy(timeout = Duration.ofSeconds(-1))
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Timeout must be positive")
        }
    }
    
    @Nested
    @DisplayName("재시도 가능 여부 확인")
    inner class RetryableTest {
        
        @ParameterizedTest
        @EnumSource(ErrorType::class)
        fun `기본 재시도 가능 에러 타입을 확인해야 한다`(errorType: ErrorType) {
            // Given
            val policy = RetryPolicy()
            
            // When
            val isRetryable = policy.isRetryable(errorType)
            
            // Then
            if (errorType in RetryPolicy.DEFAULT_RETRYABLE_ERRORS) {
                assertThat(isRetryable).isTrue()
            } else {
                assertThat(isRetryable).isFalse()
            }
        }
        
        @Test
        fun `커스텀 재시도 가능 에러 타입을 설정할 수 있다`() {
            // Given
            val customRetryableErrors = setOf(
                ErrorType.BUSINESS_LOGIC, 
                ErrorType.SERIALIZATION
            )
            val policy = RetryPolicy(retryableErrors = customRetryableErrors)
            
            // When & Then
            assertThat(policy.isRetryable(ErrorType.BUSINESS_LOGIC)).isTrue()
            assertThat(policy.isRetryable(ErrorType.SERIALIZATION)).isTrue()
            assertThat(policy.isRetryable(ErrorType.TRANSIENT)).isFalse()
            assertThat(policy.isRetryable(ErrorType.PERMANENT)).isFalse()
        }
    }
    
    @Nested
    @DisplayName("시간 기반 재시도 제한")
    inner class TimeBasedRetryTest {
        
        @Test
        fun `retryUntil 이전에는 재시도가 가능해야 한다`() {
            // Given
            val future = Instant.now().plusSeconds(60)
            val policy = RetryPolicy(retryUntil = future)
            
            // When & Then
            assertThat(policy.canRetryAt(Instant.now())).isTrue()
        }
        
        @Test
        fun `retryUntil 이후에는 재시도가 불가능해야 한다`() {
            // Given
            val past = Instant.now().minusSeconds(60)
            val policy = RetryPolicy(retryUntil = past)
            
            // When & Then
            assertThat(policy.canRetryAt(Instant.now())).isFalse()
        }
        
        @Test
        fun `retryUntil이 null이면 항상 재시도가 가능해야 한다`() {
            // Given
            val policy = RetryPolicy(retryUntil = null)
            
            // When & Then
            assertThat(policy.canRetryAt(Instant.now())).isTrue()
            assertThat(policy.canRetryAt(Instant.now().plusSeconds(3600))).isTrue()
        }
    }
    
    @Nested
    @DisplayName("재시도 횟수 확인")
    inner class RetryCountTest {
        
        @ParameterizedTest
        @CsvSource(
            "3, 0, true",
            "3, 1, true",
            "3, 2, true",
            "3, 3, false",
            "3, 4, false",
            "1, 0, true",
            "1, 1, false"
        )
        fun `남은 재시도 횟수를 확인해야 한다`(
            maxAttempts: Int,
            currentAttempts: Int,
            expectedResult: Boolean
        ) {
            // Given
            val policy = RetryPolicy(maxAttempts = maxAttempts)
            
            // When & Then
            assertThat(policy.hasRetriesLeft(currentAttempts)).isEqualTo(expectedResult)
        }
    }
    
    @Nested
    @DisplayName("사전 정의된 정책")
    inner class PredefinedPoliciesTest {
        
        @Test
        fun `NO_RETRY 정책은 재시도하지 않아야 한다`() {
            // Given
            val policy = RetryPolicy.NO_RETRY
            
            // Then
            assertThat(policy.maxAttempts).isEqualTo(1)
            assertThat(policy.backoffStrategy).isEqualTo(BackoffStrategy.NoRetry)
            assertThat(policy.retryableErrors).isEmpty()
            assertThat(policy.hasRetriesLeft(0)).isTrue()
            assertThat(policy.hasRetriesLeft(1)).isFalse()
        }
        
        @Test
        fun `AGGRESSIVE 정책은 많은 재시도를 해야 한다`() {
            // Given
            val policy = RetryPolicy.AGGRESSIVE
            
            // Then
            assertThat(policy.maxAttempts).isEqualTo(10)
            assertThat(policy.backoffStrategy).isInstanceOf(BackoffStrategy.Fixed::class.java)
            assertThat(policy.timeout).isEqualTo(Duration.ofSeconds(60))
            
            val fixedStrategy = policy.backoffStrategy as BackoffStrategy.Fixed
            assertThat(fixedStrategy.delay).isEqualTo(Duration.ofSeconds(1))
        }
        
        @Test
        fun `CONSERVATIVE 정책은 보수적으로 재시도해야 한다`() {
            // Given
            val policy = RetryPolicy.CONSERVATIVE
            
            // Then
            assertThat(policy.maxAttempts).isEqualTo(3)
            assertThat(policy.backoffStrategy).isInstanceOf(
                BackoffStrategy.ExponentialWithJitter::class.java
            )
            assertThat(policy.timeout).isEqualTo(Duration.ofSeconds(30))
            assertThat(policy.retryableErrors).containsExactlyInAnyOrder(
                ErrorType.TRANSIENT,
                ErrorType.TIMEOUT
            )
            
            val exponentialStrategy = policy.backoffStrategy as BackoffStrategy.ExponentialWithJitter
            assertThat(exponentialStrategy.baseDelay).isEqualTo(Duration.ofSeconds(5))
            assertThat(exponentialStrategy.multiplier).isEqualTo(3.0)
            assertThat(exponentialStrategy.maxDelay).isEqualTo(Duration.ofMinutes(10))
        }
    }
    
    @Nested
    @DisplayName("재시도 소진 시 동작")
    inner class RetryExhaustedActionTest {
        
        @Test
        fun `모든 재시도 소진 동작을 지원해야 한다`() {
            // Given
            val actions = RetryPolicy.RetryExhaustedAction.values()
            
            // Then
            assertThat(actions).containsExactly(
                RetryPolicy.RetryExhaustedAction.MOVE_TO_FAILED,
                RetryPolicy.RetryExhaustedAction.MOVE_TO_EXPIRED,
                RetryPolicy.RetryExhaustedAction.SKIP,
                RetryPolicy.RetryExhaustedAction.MOVE_TO_SECONDARY_DLQ,
                RetryPolicy.RetryExhaustedAction.DELETE
            )
        }
        
        @ParameterizedTest
        @EnumSource(RetryPolicy.RetryExhaustedAction::class)
        fun `각 동작으로 정책을 생성할 수 있다`(action: RetryPolicy.RetryExhaustedAction) {
            // Given & When
            val policy = RetryPolicy(onRetryExhausted = action)
            
            // Then
            assertThat(policy.onRetryExhausted).isEqualTo(action)
        }
    }
}