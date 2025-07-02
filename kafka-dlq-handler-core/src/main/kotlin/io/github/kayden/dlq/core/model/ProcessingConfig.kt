package io.github.kayden.dlq.core.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DLQ 메시지 처리 설정을 담는 data class.
 * 
 * 배치 크기, 병렬 처리 수준, 타임아웃 등 메시지 처리와 관련된
 * 모든 설정을 포함하며, 고성능 처리를 위한 튜닝 파라미터를 제공한다.
 * 
 * @property batchSize 한 번에 처리할 메시지 수
 * @property parallelism 병렬 처리 수준
 * @property processingTimeout 개별 메시지 처리 타임아웃
 * @property batchTimeout 배치 수집 타임아웃
 * @property retryPolicy 재시도 정책
 * @property enableMetrics 메트릭 수집 활성화 여부
 * @property enableTracing 추적 활성화 여부
 * @property bufferSize 내부 버퍼 크기
 * @property backpressureThreshold 백프레셔 임계값 (0.0 ~ 1.0)
 * 
 * @since 0.1.0
 */
data class ProcessingConfig(
    val batchSize: Int = 100,
    val parallelism: Int = Runtime.getRuntime().availableProcessors(),
    val processingTimeout: Duration = 30.seconds,
    val batchTimeout: Duration = 100.milliseconds,
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    val enableMetrics: Boolean = true,
    val enableTracing: Boolean = false,
    val bufferSize: Int = 10_000,
    val backpressureThreshold: Double = 0.8
) {
    init {
        require(batchSize > 0) { "Batch size must be positive" }
        require(parallelism > 0) { "Parallelism must be positive" }
        require(processingTimeout > Duration.ZERO) { "Processing timeout must be positive" }
        require(batchTimeout > Duration.ZERO) { "Batch timeout must be positive" }
        require(bufferSize > 0) { "Buffer size must be positive" }
        require(backpressureThreshold in 0.0..1.0) { 
            "Backpressure threshold must be between 0.0 and 1.0" 
        }
        require(bufferSize >= batchSize) { 
            "Buffer size must be greater than or equal to batch size" 
        }
    }
    
    /**
     * 백프레셔가 활성화되어야 하는 버퍼 크기를 계산한다.
     * 
     * @return 백프레셔 활성화 임계 버퍼 크기
     */
    fun backpressureActivationSize(): Int =
        (bufferSize * backpressureThreshold).toInt()
    
    /**
     * 주어진 메시지 수에 필요한 배치 수를 계산한다.
     * 
     * @param messageCount 전체 메시지 수
     * @return 필요한 배치 수
     */
    fun calculateBatchCount(messageCount: Int): Int =
        (messageCount + batchSize - 1) / batchSize
    
    /**
     * 예상 처리 시간을 계산한다.
     * 
     * @param messageCount 메시지 수
     * @param avgProcessingTime 평균 처리 시간
     * @return 예상 총 처리 시간
     */
    fun estimateProcessingTime(
        messageCount: Int,
        avgProcessingTime: Duration
    ): Duration {
        val batchCount = calculateBatchCount(messageCount)
        val parallelBatches = (batchCount + parallelism - 1) / parallelism
        return avgProcessingTime * parallelBatches
    }
    
    /**
     * 처리량 기반 설정을 생성한다.
     * 
     * @param targetThroughput 목표 처리량 (messages/sec)
     * @return 최적화된 설정
     */
    fun optimizeForThroughput(targetThroughput: Int): ProcessingConfig {
        val optimalBatchSize = when {
            targetThroughput > 100_000 -> 5000
            targetThroughput > 10_000 -> 1000
            targetThroughput > 1_000 -> 200
            else -> 50
        }
        
        val optimalParallelism = when {
            targetThroughput > 50_000 -> Runtime.getRuntime().availableProcessors() * 2
            targetThroughput > 10_000 -> Runtime.getRuntime().availableProcessors()
            else -> Runtime.getRuntime().availableProcessors() / 2
        }.coerceAtLeast(1)
        
        return copy(
            batchSize = optimalBatchSize,
            parallelism = optimalParallelism,
            bufferSize = optimalBatchSize * 20,
            batchTimeout = 50.milliseconds
        )
    }
    
    /**
     * 지연시간 기반 설정을 생성한다.
     * 
     * @param maxLatency 최대 허용 지연시간
     * @return 최적화된 설정
     */
    fun optimizeForLatency(maxLatency: Duration): ProcessingConfig {
        val optimalBatchSize = when {
            maxLatency < 10.milliseconds -> 1
            maxLatency < 100.milliseconds -> 10
            maxLatency < 1.seconds -> 50
            else -> 100
        }
        
        return copy(
            batchSize = optimalBatchSize,
            batchTimeout = maxLatency / 10,
            processingTimeout = maxLatency,
            bufferSize = optimalBatchSize * 10
        )
    }
    
    companion object {
        /**
         * 기본 설정.
         * 균형잡힌 처리량과 지연시간을 제공한다.
         */
        val DEFAULT = ProcessingConfig()
        
        /**
         * 고처리량 설정.
         * 초당 100K+ 메시지 처리를 목표로 한다.
         */
        val HIGH_THROUGHPUT = ProcessingConfig(
            batchSize = 5000,
            parallelism = Runtime.getRuntime().availableProcessors() * 2,
            processingTimeout = 60.seconds,
            batchTimeout = 200.milliseconds,
            bufferSize = 100_000,
            backpressureThreshold = 0.9
        )
        
        /**
         * 저지연 설정.
         * 빠른 응답 시간을 우선시한다.
         */
        val LOW_LATENCY = ProcessingConfig(
            batchSize = 10,
            parallelism = Runtime.getRuntime().availableProcessors(),
            processingTimeout = 5.seconds,
            batchTimeout = 10.milliseconds,
            bufferSize = 1000,
            backpressureThreshold = 0.7
        )
        
        /**
         * 메모리 효율적 설정.
         * 제한된 메모리 환경을 위한 설정.
         */
        val MEMORY_EFFICIENT = ProcessingConfig(
            batchSize = 50,
            parallelism = Runtime.getRuntime().availableProcessors() / 2,
            processingTimeout = 30.seconds,
            batchTimeout = 500.milliseconds,
            bufferSize = 1000,
            backpressureThreshold = 0.6
        )
        
        /**
         * 개발/테스트용 설정.
         * 디버깅과 모니터링이 용이한 설정.
         */
        val DEVELOPMENT = ProcessingConfig(
            batchSize = 1,
            parallelism = 1,
            processingTimeout = 5.seconds,
            batchTimeout = 1.seconds,
            enableMetrics = true,
            enableTracing = true,
            bufferSize = 100,
            backpressureThreshold = 0.5
        )
        
        /**
         * 시스템 리소스를 기반으로 자동 설정을 생성한다.
         * 
         * @param availableMemoryMb 사용 가능한 메모리 (MB)
         * @param targetThroughput 목표 처리량 (optional)
         * @return 최적화된 설정
         */
        fun autoConfig(
            availableMemoryMb: Long = Runtime.getRuntime().maxMemory() / 1024 / 1024,
            targetThroughput: Int? = null
        ): ProcessingConfig {
            val cores = Runtime.getRuntime().availableProcessors()
            
            // 메모리 기반 버퍼 크기 계산 (메시지당 1KB 가정)
            val maxBufferSize = (availableMemoryMb * 1024 / 10).toInt() // 10% of memory
            
            // CPU 코어 기반 병렬 처리 수준
            val parallelism = when {
                cores >= 16 -> cores
                cores >= 8 -> cores - 1
                cores >= 4 -> cores - 1
                else -> cores
            }
            
            // 목표 처리량 기반 배치 크기
            val batchSize = targetThroughput?.let { throughput ->
                when {
                    throughput > 100_000 -> 5000
                    throughput > 10_000 -> 1000
                    throughput > 1_000 -> 200
                    else -> 100
                }
            } ?: 1000
            
            return ProcessingConfig(
                batchSize = batchSize,
                parallelism = parallelism,
                bufferSize = minOf(maxBufferSize, batchSize * 20),
                backpressureThreshold = if (availableMemoryMb < 1024) 0.7 else 0.8
            )
        }
    }
}

/**
 * ProcessingConfig를 위한 빌더 클래스.
 * 
 * 플루언트 API를 제공하여 설정을 쉽게 구성할 수 있다.
 */
class ProcessingConfigBuilder {
    private var batchSize: Int = 100
    private var parallelism: Int = Runtime.getRuntime().availableProcessors()
    private var processingTimeout: Duration = 30.seconds
    private var batchTimeout: Duration = 100.milliseconds
    private var retryPolicy: RetryPolicy = RetryPolicy.DEFAULT
    private var enableMetrics: Boolean = true
    private var enableTracing: Boolean = false
    private var bufferSize: Int = 10_000
    private var backpressureThreshold: Double = 0.8
    
    fun batchSize(size: Int) = apply { this.batchSize = size }
    fun parallelism(level: Int) = apply { this.parallelism = level }
    fun processingTimeout(timeout: Duration) = apply { this.processingTimeout = timeout }
    fun batchTimeout(timeout: Duration) = apply { this.batchTimeout = timeout }
    fun retryPolicy(policy: RetryPolicy) = apply { this.retryPolicy = policy }
    fun enableMetrics(enable: Boolean = true) = apply { this.enableMetrics = enable }
    fun enableTracing(enable: Boolean = true) = apply { this.enableTracing = enable }
    fun bufferSize(size: Int) = apply { this.bufferSize = size }
    fun backpressureThreshold(threshold: Double) = apply { this.backpressureThreshold = threshold }
    
    fun build(): ProcessingConfig = ProcessingConfig(
        batchSize = batchSize,
        parallelism = parallelism,
        processingTimeout = processingTimeout,
        batchTimeout = batchTimeout,
        retryPolicy = retryPolicy,
        enableMetrics = enableMetrics,
        enableTracing = enableTracing,
        bufferSize = bufferSize,
        backpressureThreshold = backpressureThreshold
    )
}

/**
 * ProcessingConfig 빌더를 생성하는 헬퍼 함수.
 */
fun processingConfig(block: ProcessingConfigBuilder.() -> Unit): ProcessingConfig =
    ProcessingConfigBuilder().apply(block).build()