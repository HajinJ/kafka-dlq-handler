package io.github.kayden.dlq.domain.value

import java.time.Duration
import kotlin.math.pow

/**
 * 재시도 간격을 계산하는 백오프 전략의 기본 인터페이스
 */
sealed class BackoffStrategy {
    /**
     * 재시도 횟수에 따른 대기 시간을 계산한다.
     * 
     * @param attemptNumber 현재 시도 횟수 (1부터 시작)
     * @return 다음 재시도까지 대기할 시간
     */
    abstract fun calculateDelay(attemptNumber: Int): Duration
}

/**
 * 고정된 간격으로 재시도하는 백오프 전략
 * 
 * @property delay 각 재시도 사이의 고정된 대기 시간
 */
class FixedBackoff(
    private val delay: Duration
) : BackoffStrategy() {
    init {
        require(!delay.isNegative) { 
            "Delay must not be negative" 
        }
    }
    
    override fun calculateDelay(attemptNumber: Int): Duration = delay
}

/**
 * 지수적으로 증가하는 간격으로 재시도하는 백오프 전략
 * 
 * @property initialDelay 첫 번째 재시도의 대기 시간
 * @property multiplier 각 재시도마다 대기 시간에 곱해지는 계수
 * @property maxDelay 최대 대기 시간 (이 값을 초과하지 않음)
 */
class ExponentialBackoff(
    private val initialDelay: Duration = Duration.ofSeconds(1),
    private val multiplier: Double = 2.0,
    private val maxDelay: Duration = Duration.ofMinutes(5)
) : BackoffStrategy() {
    init {
        require(!initialDelay.isNegative) { 
            "Initial delay must not be negative" 
        }
        require(multiplier >= 1.0) { 
            "Multiplier must be at least 1.0" 
        }
        require(!maxDelay.isNegative) { 
            "Max delay must not be negative" 
        }
        require(maxDelay >= initialDelay) { 
            "Max delay must be greater than or equal to initial delay" 
        }
    }
    
    override fun calculateDelay(attemptNumber: Int): Duration {
        require(attemptNumber > 0) { 
            "Attempt number must be positive" 
        }
        
        val delay = initialDelay.toMillis() * multiplier.pow(attemptNumber - 1)
        return Duration.ofMillis(minOf(delay.toLong(), maxDelay.toMillis()))
    }
}