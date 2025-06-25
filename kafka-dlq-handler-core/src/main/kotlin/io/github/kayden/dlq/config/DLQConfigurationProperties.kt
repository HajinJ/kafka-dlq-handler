package io.github.kayden.dlq.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Kafka DLQ Handler 설정 프로퍼티
 * 
 * application.yml 또는 application.properties에서 설정 가능하다.
 * 
 * @sample
 * ```yaml
 * kafka:
 *   dlq:
 *     enabled: true
 *     topic-pattern: ".*\\.DLQ"
 *     processing:
 *       batch-size: 1000
 *       workers: 0  # 0이면 자동 설정
 * ```
 */
@ConfigurationProperties(prefix = "kafka.dlq")
data class DLQConfigurationProperties(
    /**
     * DLQ 처리 활성화 여부
     */
    val enabled: Boolean = true,
    
    /**
     * DLQ 토픽 패턴 (정규식)
     */
    val topicPattern: String = ".*\\.DLQ",
    
    /**
     * 저장소 관련 설정
     */
    val storage: StorageProperties = StorageProperties(),
    
    /**
     * 처리 설정
     */
    val processing: ProcessingProperties = ProcessingProperties()
)

/**
 * 저장소 관련 설정
 */
data class StorageProperties(
    /**
     * 외부 저장소 버킷/경로
     */
    val externalBucket: String = "dlq-payloads",
    
    /**
     * 대용량 메시지 외부 저장 활성화
     */
    val enableExternalStorage: Boolean = true
)

/**
 * 처리 설정
 */
data class ProcessingProperties(
    /**
     * 워커 수 (0이면 CPU 코어 수에 따라 자동 설정)
     */
    val workers: Int = 0,
    
    /**
     * 배치 크기 (자동 최적화됨)
     */
    val batchSize: Int = 1000,
    
    /**
     * 배치 플러시 간격 (ms)
     */
    val flushIntervalMs: Long = 5000,
    
    /**
     * 대용량 메시지 임계값 (bytes)
     */
    val largeMessageThreshold: Int = 102400, // 100KB
    
    /**
     * 자동 최적화 활성화
     */
    val autoOptimize: Boolean = true
)