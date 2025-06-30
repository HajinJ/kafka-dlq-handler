package io.github.kayden.dlq.core.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

@DisplayName("BackoffStrategy 테스트")
class BackoffStrategyTest {
    
    @Nested
    @DisplayName("Fixed 백오프 전략")
    inner class FixedBackoffTest {
        
        @Test
        fun `고정된 지연 시간을 반환해야 한다`() {
            // Given
            val delay = Duration.ofSeconds(5)
            val strategy = BackoffStrategy.Fixed(delay)
            
            // When & Then
            assertThat(strategy.calculateDelay(1)).isEqualTo(delay)
            assertThat(strategy.calculateDelay(5)).isEqualTo(delay)
            assertThat(strategy.calculateDelay(10)).isEqualTo(delay)
        }
        
        @Test
        fun `음수 또는 0 지연 시간은 허용하지 않는다`() {
            assertThatThrownBy { 
                BackoffStrategy.Fixed(Duration.ZERO) 
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Delay must be positive")
            
            assertThatThrownBy { 
                BackoffStrategy.Fixed(Duration.ofSeconds(-1)) 
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
    
    @Nested
    @DisplayName("Exponential 백오프 전략")
    inner class ExponentialBackoffTest {
        
        @Test
        fun `지수적으로 증가하는 지연 시간을 반환해야 한다`() {
            // Given
            val strategy = BackoffStrategy.Exponential(
                baseDelay = Duration.ofSeconds(1),
                multiplier = 2.0
            )
            
            // When & Then
            assertThat(strategy.calculateDelay(1)).isEqualTo(Duration.ofSeconds(1))
            assertThat(strategy.calculateDelay(2)).isEqualTo(Duration.ofSeconds(2))
            assertThat(strategy.calculateDelay(3)).isEqualTo(Duration.ofSeconds(4))
            assertThat(strategy.calculateDelay(4)).isEqualTo(Duration.ofSeconds(8))
        }
        
        @Test
        fun `최대 지연 시간을 초과하지 않아야 한다`() {
            // Given
            val maxDelay = Duration.ofSeconds(10)
            val strategy = BackoffStrategy.Exponential(
                baseDelay = Duration.ofSeconds(1),
                multiplier = 3.0,
                maxDelay = maxDelay
            )
            
            // When & Then
            assertThat(strategy.calculateDelay(1)).isEqualTo(Duration.ofSeconds(1))
            assertThat(strategy.calculateDelay(2)).isEqualTo(Duration.ofSeconds(3))
            assertThat(strategy.calculateDelay(3)).isEqualTo(Duration.ofSeconds(9))
            assertThat(strategy.calculateDelay(4)).isEqualTo(maxDelay)
            assertThat(strategy.calculateDelay(10)).isEqualTo(maxDelay)
        }
        
        @ParameterizedTest
        @ValueSource(doubles = [0.5, 1.0, -1.0])
        fun `승수는 1보다 커야 한다`(multiplier: Double) {
            assertThatThrownBy {
                BackoffStrategy.Exponential(
                    baseDelay = Duration.ofSeconds(1),
                    multiplier = multiplier
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Multiplier must be greater than 1")
        }
        
        @Test
        fun `유효하지 않은 파라미터를 검증해야 한다`() {
            assertThatThrownBy {
                BackoffStrategy.Exponential(
                    baseDelay = Duration.ZERO
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Base delay must be positive")
            
            assertThatThrownBy {
                BackoffStrategy.Exponential(
                    baseDelay = Duration.ofSeconds(10),
                    maxDelay = Duration.ofSeconds(5)
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Max delay must be >= base delay")
        }
    }
    
    @Nested
    @DisplayName("Linear 백오프 전략")
    inner class LinearBackoffTest {
        
        @Test
        fun `선형적으로 증가하는 지연 시간을 반환해야 한다`() {
            // Given
            val strategy = BackoffStrategy.Linear(
                initialDelay = Duration.ofSeconds(2),
                increment = Duration.ofSeconds(3)
            )
            
            // When & Then
            assertThat(strategy.calculateDelay(1)).isEqualTo(Duration.ofSeconds(2))
            assertThat(strategy.calculateDelay(2)).isEqualTo(Duration.ofSeconds(5))
            assertThat(strategy.calculateDelay(3)).isEqualTo(Duration.ofSeconds(8))
            assertThat(strategy.calculateDelay(4)).isEqualTo(Duration.ofSeconds(11))
        }
        
        @Test
        fun `최대 지연 시간을 초과하지 않아야 한다`() {
            // Given
            val maxDelay = Duration.ofSeconds(10)
            val strategy = BackoffStrategy.Linear(
                initialDelay = Duration.ofSeconds(3),
                increment = Duration.ofSeconds(4),
                maxDelay = maxDelay
            )
            
            // When & Then
            assertThat(strategy.calculateDelay(1)).isEqualTo(Duration.ofSeconds(3))
            assertThat(strategy.calculateDelay(2)).isEqualTo(Duration.ofSeconds(7))
            assertThat(strategy.calculateDelay(3)).isEqualTo(maxDelay)
            assertThat(strategy.calculateDelay(10)).isEqualTo(maxDelay)
        }
        
        @Test
        fun `유효하지 않은 파라미터를 검증해야 한다`() {
            assertThatThrownBy {
                BackoffStrategy.Linear(
                    initialDelay = Duration.ZERO,
                    increment = Duration.ofSeconds(1)
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Initial delay must be positive")
            
            assertThatThrownBy {
                BackoffStrategy.Linear(
                    initialDelay = Duration.ofSeconds(1),
                    increment = Duration.ZERO
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Increment must be positive")
            
            assertThatThrownBy {
                BackoffStrategy.Linear(
                    initialDelay = Duration.ofSeconds(10),
                    increment = Duration.ofSeconds(1),
                    maxDelay = Duration.ofSeconds(5)
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Max delay must be >= initial delay")
        }
    }
    
    @Nested
    @DisplayName("ExponentialWithJitter 백오프 전략")
    inner class ExponentialWithJitterTest {
        
        @Test
        fun `지터가 포함된 지연 시간을 반환해야 한다`() {
            // Given
            val baseDelay = Duration.ofSeconds(1)
            val strategy = BackoffStrategy.ExponentialWithJitter(
                baseDelay = baseDelay,
                multiplier = 2.0,
                jitterFactor = 0.1
            )
            
            // When
            val delays = (1..10).map { strategy.calculateDelay(1) }
            
            // Then - 모든 지연 시간이 같지 않아야 함 (지터 때문에)
            assertThat(delays.toSet().size).isGreaterThan(1)
            
            // 지연 시간은 기본값의 ±10% 범위 내에 있어야 함
            delays.forEach { delay ->
                assertThat(delay.toMillis()).isBetween(900L, 1100L)
            }
        }
        
        @Test
        fun `지터 팩터는 0과 1 사이여야 한다`() {
            assertThatThrownBy {
                BackoffStrategy.ExponentialWithJitter(
                    baseDelay = Duration.ofSeconds(1),
                    jitterFactor = -0.1
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Jitter factor must be between 0 and 1")
            
            assertThatThrownBy {
                BackoffStrategy.ExponentialWithJitter(
                    baseDelay = Duration.ofSeconds(1),
                    jitterFactor = 1.1
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Jitter factor must be between 0 and 1")
        }
        
        @Test
        fun `지연 시간은 항상 양수여야 한다`() {
            // Given
            val strategy = BackoffStrategy.ExponentialWithJitter(
                baseDelay = Duration.ofMillis(10),
                jitterFactor = 0.9 // 큰 지터로 음수 가능성 테스트
            )
            
            // When & Then
            repeat(100) { attemptNumber ->
                val delay = strategy.calculateDelay(attemptNumber + 1)
                assertThat(delay.toMillis()).isPositive()
            }
        }
    }
    
    @Nested
    @DisplayName("NoRetry 백오프 전략")
    inner class NoRetryTest {
        
        @Test
        fun `항상 0 지연 시간을 반환해야 한다`() {
            // Given
            val strategy = BackoffStrategy.NoRetry
            
            // When & Then
            assertThat(strategy.calculateDelay(1)).isEqualTo(Duration.ZERO)
            assertThat(strategy.calculateDelay(100)).isEqualTo(Duration.ZERO)
            assertThat(strategy.calculateDelay(1000)).isEqualTo(Duration.ZERO)
        }
    }
}