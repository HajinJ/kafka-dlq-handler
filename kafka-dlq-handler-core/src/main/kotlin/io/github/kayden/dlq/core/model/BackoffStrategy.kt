package io.github.kayden.dlq.core.model

import java.time.Duration
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * 재시도 간격을 계산하는 백오프 전략을 정의한다.
 *
 * 이 sealed class는 다양한 백오프 알고리즘을 제공하여
 * 재시도 시 발생하는 부하를 분산시키고 시스템의 안정성을 높인다.
 *
 * @see RetryPolicy
 * @since 0.1.0
 */
sealed class BackoffStrategy {
    
    /**
     * 주어진 재시도 횟수에 대한 지연 시간을 계산한다.
     *
     * @param attemptNumber 재시도 횟수 (1부터 시작)
     * @return 다음 재시도까지의 지연 시간
     */
    abstract fun calculateDelay(attemptNumber: Int): Duration
    
    /**
     * 고정된 간격으로 재시도하는 전략
     *
     * @property delay 각 재시도 사이의 고정된 지연 시간
     */
    data class Fixed(
        val delay: Duration
    ) : BackoffStrategy() {
        init {
            require(delay.toMillis() > 0) { 
                "Delay must be positive" 
            }
        }
        
        override fun calculateDelay(attemptNumber: Int): Duration = delay
    }
    
    /**
     * 지수적으로 증가하는 간격으로 재시도하는 전략
     *
     * @property baseDelay 기본 지연 시간
     * @property multiplier 각 재시도마다 곱해지는 승수
     * @property maxDelay 최대 지연 시간
     */
    data class Exponential(
        val baseDelay: Duration,
        val multiplier: Double = 2.0,
        val maxDelay: Duration = Duration.ofMinutes(5)
    ) : BackoffStrategy() {
        init {
            require(baseDelay.toMillis() > 0) { 
                "Base delay must be positive" 
            }
            require(multiplier > 1.0) { 
                "Multiplier must be greater than 1" 
            }
            require(maxDelay >= baseDelay) { 
                "Max delay must be >= base delay" 
            }
        }
        
        override fun calculateDelay(attemptNumber: Int): Duration {
            val exponentialDelay = baseDelay.toMillis() * 
                multiplier.pow(attemptNumber - 1)
            
            return Duration.ofMillis(
                min(exponentialDelay.toLong(), maxDelay.toMillis())
            )
        }
    }
    
    /**
     * 선형적으로 증가하는 간격으로 재시도하는 전략
     *
     * @property initialDelay 첫 번째 재시도의 지연 시간
     * @property increment 각 재시도마다 추가되는 시간
     * @property maxDelay 최대 지연 시간
     */
    data class Linear(
        val initialDelay: Duration,
        val increment: Duration,
        val maxDelay: Duration = Duration.ofMinutes(5)
    ) : BackoffStrategy() {
        init {
            require(initialDelay.toMillis() > 0) { 
                "Initial delay must be positive" 
            }
            require(increment.toMillis() > 0) { 
                "Increment must be positive" 
            }
            require(maxDelay >= initialDelay) { 
                "Max delay must be >= initial delay" 
            }
        }
        
        override fun calculateDelay(attemptNumber: Int): Duration {
            val linearDelay = initialDelay.toMillis() + 
                (increment.toMillis() * (attemptNumber - 1))
            
            return Duration.ofMillis(
                min(linearDelay, maxDelay.toMillis())
            )
        }
    }
    
    /**
     * 지수적 백오프에 무작위성을 추가한 전략
     * 
     * Jitter를 추가하여 여러 클라이언트가 동시에 재시도하는 
     * "thundering herd" 문제를 방지한다.
     *
     * @property baseDelay 기본 지연 시간
     * @property multiplier 각 재시도마다 곱해지는 승수
     * @property maxDelay 최대 지연 시간
     * @property jitterFactor Jitter 비율 (0.0 ~ 1.0)
     */
    data class ExponentialWithJitter(
        val baseDelay: Duration,
        val multiplier: Double = 2.0,
        val maxDelay: Duration = Duration.ofMinutes(5),
        val jitterFactor: Double = 0.1
    ) : BackoffStrategy() {
        init {
            require(baseDelay.toMillis() > 0) { 
                "Base delay must be positive" 
            }
            require(multiplier > 1.0) { 
                "Multiplier must be greater than 1" 
            }
            require(maxDelay >= baseDelay) { 
                "Max delay must be >= base delay" 
            }
            require(jitterFactor in 0.0..1.0) { 
                "Jitter factor must be between 0 and 1" 
            }
        }
        
        override fun calculateDelay(attemptNumber: Int): Duration {
            val exponentialDelay = baseDelay.toMillis() * 
                multiplier.pow(attemptNumber - 1)
            
            val cappedDelay = min(
                exponentialDelay.toLong(), 
                maxDelay.toMillis()
            )
            
            val jitter = (Random.nextDouble() - 0.5) * 
                2 * jitterFactor * cappedDelay
            
            return Duration.ofMillis(
                (cappedDelay + jitter).toLong().coerceAtLeast(1)
            )
        }
    }
    
    /**
     * 재시도하지 않는 전략
     */
    object NoRetry : BackoffStrategy() {
        override fun calculateDelay(attemptNumber: Int): Duration = 
            Duration.ZERO
    }
}