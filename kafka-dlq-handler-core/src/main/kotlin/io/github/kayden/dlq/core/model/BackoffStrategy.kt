package io.github.kayden.dlq.core.model

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DLQ 메시지 재처리를 위한 백오프 전략을 정의하는 sealed class.
 * 
 * 백오프 전략은 재시도 간격을 결정하며, 시스템 부하를 분산시키고
 * 일시적 장애를 극복하는 데 중요한 역할을 한다.
 * 
 * @since 0.1.0
 */
sealed class BackoffStrategy {
    
    /**
     * 주어진 시도 횟수에 대한 백오프 지연 시간을 계산한다.
     * 
     * @param attemptNumber 현재 시도 번호 (1부터 시작)
     * @return 대기해야 할 지연 시간
     */
    abstract fun calculateDelay(attemptNumber: Int): Duration
    
    /**
     * 백오프 없음. 즉시 재시도한다.
     */
    object None : BackoffStrategy() {
        override fun calculateDelay(attemptNumber: Int): Duration = Duration.ZERO
        override fun toString(): String = "BackoffStrategy.None"
    }
    
    /**
     * 고정 지연 백오프. 항상 동일한 시간만큼 대기한다.
     * 
     * @property delay 고정 지연 시간
     */
    data class Fixed(
        val delay: Duration
    ) : BackoffStrategy() {
        init {
            require(delay >= Duration.ZERO) { "Delay must not be negative" }
        }
        
        override fun calculateDelay(attemptNumber: Int): Duration = delay
        
        companion object {
            /**
             * 자주 사용되는 고정 지연 전략들.
             */
            val ONE_SECOND = Fixed(1.seconds)
            val FIVE_SECONDS = Fixed(5.seconds)
            val TEN_SECONDS = Fixed(10.seconds)
            val THIRTY_SECONDS = Fixed(30.seconds)
            val ONE_MINUTE = Fixed(60.seconds)
        }
    }
    
    /**
     * 선형 증가 백오프. 시도 횟수에 비례하여 지연 시간이 증가한다.
     * 
     * @property initialDelay 초기 지연 시간
     * @property increment 시도마다 증가하는 시간
     * @property maxDelay 최대 지연 시간 (optional)
     */
    data class Linear(
        val initialDelay: Duration,
        val increment: Duration,
        val maxDelay: Duration? = null
    ) : BackoffStrategy() {
        init {
            require(initialDelay >= Duration.ZERO) { "Initial delay must not be negative" }
            require(increment >= Duration.ZERO) { "Increment must not be negative" }
            maxDelay?.let {
                require(it >= initialDelay) { "Max delay must be greater than or equal to initial delay" }
            }
        }
        
        override fun calculateDelay(attemptNumber: Int): Duration {
            require(attemptNumber > 0) { "Attempt number must be positive" }
            
            val delay = initialDelay + increment * (attemptNumber - 1)
            return maxDelay?.let { min(delay, it) } ?: delay
        }
        
        companion object {
            /**
             * 기본 선형 백오프 전략.
             * 1초부터 시작하여 매번 1초씩 증가, 최대 60초.
             */
            val DEFAULT = Linear(
                initialDelay = 1.seconds,
                increment = 1.seconds,
                maxDelay = 60.seconds
            )
        }
    }
    
    /**
     * 지수 증가 백오프. 지연 시간이 지수적으로 증가한다.
     * 
     * @property baseDelay 기본 지연 시간
     * @property multiplier 지수 승수 (기본값: 2.0)
     * @property maxDelay 최대 지연 시간 (optional)
     */
    data class Exponential(
        val baseDelay: Duration,
        val multiplier: Double = 2.0,
        val maxDelay: Duration? = null
    ) : BackoffStrategy() {
        init {
            require(baseDelay >= Duration.ZERO) { "Base delay must not be negative" }
            require(multiplier > 1.0) { "Multiplier must be greater than 1" }
            maxDelay?.let {
                require(it >= baseDelay) { "Max delay must be greater than or equal to base delay" }
            }
        }
        
        override fun calculateDelay(attemptNumber: Int): Duration {
            require(attemptNumber > 0) { "Attempt number must be positive" }
            
            val delayMillis = baseDelay.inWholeMilliseconds * 
                multiplier.pow(attemptNumber - 1).toLong()
            val delay = delayMillis.milliseconds
            
            return maxDelay?.let { min(delay, it) } ?: delay
        }
        
        companion object {
            /**
             * 기본 지수 백오프 전략.
             * 1초부터 시작하여 2배씩 증가, 최대 5분.
             */
            val DEFAULT = Exponential(
                baseDelay = 1.seconds,
                multiplier = 2.0,
                maxDelay = 300.seconds
            )
            
            /**
             * 빠른 재시도를 위한 전략.
             * 100ms부터 시작하여 2배씩 증가, 최대 10초.
             */
            val FAST = Exponential(
                baseDelay = 100.milliseconds,
                multiplier = 2.0,
                maxDelay = 10.seconds
            )
        }
    }
    
    /**
     * 지터를 포함한 지수 백오프.
     * 여러 클라이언트의 동시 재시도로 인한 부하 집중을 방지한다.
     * 
     * @property baseStrategy 기본 지수 백오프 전략
     * @property jitterFactor 지터 팩터 (0.0 ~ 1.0)
     * @property fullJitter true면 전체 지터, false면 부분 지터 적용
     */
    data class ExponentialWithJitter(
        val baseStrategy: Exponential,
        val jitterFactor: Double = 0.1,
        val fullJitter: Boolean = false
    ) : BackoffStrategy() {
        init {
            require(jitterFactor in 0.0..1.0) { "Jitter factor must be between 0 and 1" }
        }
        
        override fun calculateDelay(attemptNumber: Int): Duration {
            val baseDelay = baseStrategy.calculateDelay(attemptNumber)
            
            return if (fullJitter) {
                // Full jitter: delay = random(0, baseDelay)
                val randomMillis = Random.nextLong(baseDelay.inWholeMilliseconds + 1)
                randomMillis.milliseconds
            } else {
                // Partial jitter: delay = baseDelay * (1 - jitterFactor/2) + random(0, baseDelay * jitterFactor)
                val minDelay = baseDelay.inWholeMilliseconds * (1 - jitterFactor / 2)
                val jitterRange = baseDelay.inWholeMilliseconds * jitterFactor
                val delay = minDelay + Random.nextDouble(jitterRange)
                delay.toLong().milliseconds
            }
        }
        
        companion object {
            /**
             * 기본 지터 전략.
             * 기본 지수 백오프에 10% 부분 지터 적용.
             */
            val DEFAULT = ExponentialWithJitter(
                baseStrategy = Exponential.DEFAULT,
                jitterFactor = 0.1,
                fullJitter = false
            )
            
            /**
             * 전체 지터 전략.
             * AWS가 권장하는 방식으로, 최대한의 부하 분산 효과.
             */
            val FULL_JITTER = ExponentialWithJitter(
                baseStrategy = Exponential.DEFAULT,
                jitterFactor = 1.0,
                fullJitter = true
            )
        }
    }
    
    /**
     * 사용자 정의 백오프 전략.
     * 
     * @property delayFunction 시도 횟수를 받아 지연 시간을 반환하는 함수
     */
    data class Custom(
        val name: String = "Custom",
        val delayFunction: (Int) -> Duration
    ) : BackoffStrategy() {
        override fun calculateDelay(attemptNumber: Int): Duration {
            require(attemptNumber > 0) { "Attempt number must be positive" }
            val delay = delayFunction(attemptNumber)
            require(delay >= Duration.ZERO) { "Delay must not be negative" }
            return delay
        }
        
        override fun toString(): String = "BackoffStrategy.Custom($name)"
    }
    
    companion object {
        /**
         * 에러 타입에 따른 권장 백오프 전략을 반환한다.
         * 
         * @param errorType 에러 타입
         * @return 권장 백오프 전략
         */
        fun recommendedFor(errorType: ErrorType): BackoffStrategy {
            return when (errorType) {
                ErrorType.TRANSIENT_NETWORK_ERROR -> Exponential.DEFAULT
                ErrorType.TRANSIENT_SERVICE_ERROR -> ExponentialWithJitter.DEFAULT
                ErrorType.RESOURCE_EXHAUSTED -> Linear.DEFAULT
                ErrorType.AUTHENTICATION_ERROR -> Fixed.THIRTY_SECONDS
                else -> None
            }
        }
        
        /**
         * 피보나치 수열 백오프 전략을 생성한다.
         * 
         * @param baseDelay 기본 지연 시간
         * @param maxDelay 최대 지연 시간
         * @return 피보나치 백오프 전략
         */
        fun fibonacci(
            baseDelay: Duration = 1.seconds,
            maxDelay: Duration = 60.seconds
        ): Custom {
            var prev = 0L
            var curr = 1L
            
            return Custom("Fibonacci") { attemptNumber ->
                if (attemptNumber == 1) {
                    baseDelay
                } else {
                    repeat(attemptNumber - 2) {
                        val next = prev + curr
                        prev = curr
                        curr = next
                    }
                    val delay = baseDelay.inWholeMilliseconds * curr
                    min(delay.milliseconds, maxDelay)
                }
            }
        }
    }
}