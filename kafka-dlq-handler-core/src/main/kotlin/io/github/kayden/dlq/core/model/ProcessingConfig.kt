package io.github.kayden.dlq.core.model

import java.time.Duration

/**
 * DLQ 메시지 처리를 위한 설정을 정의한다.
 *
 * 이 클래스는 배치 처리, 병렬 처리, 백프레셔 관리 등
 * 고성능 메시지 처리를 위한 모든 설정을 포함한다.
 *
 * @property batchSize 한 번에 처리할 메시지 수
 * @property parallelism 병렬 처리 수준
 * @property processingTimeout 개별 메시지 처리 타임아웃
 * @property batchTimeout 배치 수집 타임아웃
 * @property retryPolicy 재시도 정책
 * @property enableMetrics 메트릭 수집 활성화 여부
 * @property enableTracing 추적 활성화 여부
 * @property processingMode 처리 모드
 *
 * @see RetryPolicy
 * @since 0.1.0
 */
data class ProcessingConfig(
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val parallelism: Int = DEFAULT_PARALLELISM,
    val processingTimeout: Duration = DEFAULT_PROCESSING_TIMEOUT,
    val batchTimeout: Duration = DEFAULT_BATCH_TIMEOUT,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val enableMetrics: Boolean = true,
    val enableTracing: Boolean = false,
    val processingMode: ProcessingMode = ProcessingMode.BATCH
) {
    init {
        require(batchSize > 0) { 
            "Batch size must be positive" 
        }
        require(batchSize <= MAX_BATCH_SIZE) { 
            "Batch size cannot exceed $MAX_BATCH_SIZE" 
        }
        require(parallelism > 0) { 
            "Parallelism must be positive" 
        }
        require(parallelism <= MAX_PARALLELISM) { 
            "Parallelism cannot exceed $MAX_PARALLELISM" 
        }
        require(processingTimeout.toMillis() > 0) { 
            "Processing timeout must be positive" 
        }
        require(batchTimeout.toMillis() > 0) { 
            "Batch timeout must be positive" 
        }
    }
    
    /**
     * 처리 모드
     */
    enum class ProcessingMode {
        /** 메시지를 개별적으로 처리 */
        SINGLE,
        
        /** 메시지를 배치로 처리 (권장) */
        BATCH,
        
        /** 스트리밍 모드로 처리 */
        STREAMING
    }
    
    /**
     * 고성능 처리를 위한 최적화된 설정인지 확인한다.
     *
     * @return 고성능 최적화 여부
     */
    fun isHighPerformance(): Boolean = 
        batchSize >= 1000 && 
        parallelism >= Runtime.getRuntime().availableProcessors() &&
        processingMode == ProcessingMode.BATCH
    
    /**
     * 예상 처리량을 계산한다.
     *
     * @return 초당 예상 처리 메시지 수
     */
    fun estimatedThroughput(): Int {
        val batchesPerSecond = 1000.0 / batchTimeout.toMillis()
        val messagesPerBatch = batchSize
        val effectiveParallelism = when (processingMode) {
            ProcessingMode.SINGLE -> 1
            ProcessingMode.BATCH -> parallelism
            ProcessingMode.STREAMING -> parallelism * 2
        }
        
        return (batchesPerSecond * messagesPerBatch * effectiveParallelism).toInt()
    }
    
    companion object {
        /** 기본 배치 크기 */
        const val DEFAULT_BATCH_SIZE = 100
        
        /** 최대 배치 크기 */
        const val MAX_BATCH_SIZE = 10_000
        
        /** 기본 병렬 처리 수준 */
        val DEFAULT_PARALLELISM = Runtime.getRuntime().availableProcessors()
        
        /** 최대 병렬 처리 수준 */
        const val MAX_PARALLELISM = 256
        
        /** 기본 처리 타임아웃 */
        val DEFAULT_PROCESSING_TIMEOUT: Duration = Duration.ofSeconds(30)
        
        /** 기본 배치 타임아웃 */
        val DEFAULT_BATCH_TIMEOUT: Duration = Duration.ofMillis(100)
        
        /** 개발 환경용 설정 */
        val DEVELOPMENT = ProcessingConfig(
            batchSize = 10,
            parallelism = 2,
            processingTimeout = Duration.ofSeconds(60),
            batchTimeout = Duration.ofSeconds(1),
            enableMetrics = true,
            enableTracing = true
        )
        
        /** 프로덕션 환경용 기본 설정 */
        val PRODUCTION = ProcessingConfig(
            batchSize = 1000,
            parallelism = DEFAULT_PARALLELISM,
            processingTimeout = Duration.ofSeconds(30),
            batchTimeout = Duration.ofMillis(100),
            enableMetrics = true,
            enableTracing = false
        )
        
        /** 고성능 환경용 설정 */
        val HIGH_PERFORMANCE = ProcessingConfig(
            batchSize = 5000,
            parallelism = DEFAULT_PARALLELISM * 2,
            processingTimeout = Duration.ofSeconds(10),
            batchTimeout = Duration.ofMillis(50),
            retryPolicy = RetryPolicy.AGGRESSIVE,
            enableMetrics = false,
            enableTracing = false,
            processingMode = ProcessingMode.BATCH
        )
        
        /** 저지연 환경용 설정 */
        val LOW_LATENCY = ProcessingConfig(
            batchSize = 50,
            parallelism = DEFAULT_PARALLELISM * 4,
            processingTimeout = Duration.ofSeconds(5),
            batchTimeout = Duration.ofMillis(10),
            enableMetrics = true,
            enableTracing = false,
            processingMode = ProcessingMode.STREAMING
        )
    }
}