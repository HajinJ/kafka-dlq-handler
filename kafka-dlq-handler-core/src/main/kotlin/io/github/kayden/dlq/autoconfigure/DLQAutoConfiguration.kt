package io.github.kayden.dlq.autoconfigure

import io.github.kayden.dlq.config.DLQConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.kafka.core.KafkaTemplate
import jakarta.persistence.EntityManager

/**
 * Kafka DLQ Handler 자동 설정 클래스
 * 
 * Spring Boot의 자동 설정 메커니즘을 통해 DLQ 처리에 필요한
 * 모든 컴포넌트를 자동으로 구성한다.
 * 
 * @see DLQConfigurationProperties
 */
@Configuration
@ConditionalOnClass(KafkaTemplate::class, EntityManager::class)
@ConditionalOnProperty(
    prefix = "kafka.dlq",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
@EnableConfigurationProperties(DLQConfigurationProperties::class)
@AutoConfigureAfter(
    KafkaAutoConfiguration::class,
    JpaRepositoriesAutoConfiguration::class
)
@ComponentScan(basePackages = ["io.github.kayden.dlq"])
@EnableJpaRepositories(basePackages = ["io.github.kayden.dlq.repository"])
class DLQAutoConfiguration {
    
    private val logger = LoggerFactory.getLogger(javaClass)
    
    init {
        logger.info("Initializing Kafka DLQ Handler Auto Configuration")
    }
    
    /**
     * DLQ 설정 유효성 검증 빈
     */
    @Bean
    fun dlqConfigurationValidator(
        properties: DLQConfigurationProperties
    ): DLQConfigurationValidator {
        return DLQConfigurationValidator(properties).also {
            it.validate()
        }
    }
    
    /**
     * DLQ 기능 정보 로깅 빈
     */
    @Bean
    fun dlqFeatureLogger(
        properties: DLQConfigurationProperties
    ): DLQFeatureLogger {
        return DLQFeatureLogger(properties)
    }
}


/**
 * DLQ 설정 유효성 검증기
 */
class DLQConfigurationValidator(
    private val properties: DLQConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    fun validate() {
        validateTopicPattern()
        validateProcessing()
        
        logger.info("DLQ configuration validation completed successfully")
    }
    
    private fun validateTopicPattern() {
        try {
            Regex(properties.topicPattern)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Invalid topic pattern: ${properties.topicPattern}", e
            )
        }
    }
    
    private fun validateProcessing() {
        require(properties.processing.batchSize > 0) {
            "Batch size must be positive: ${properties.processing.batchSize}"
        }
        require(properties.processing.flushIntervalMs > 0) {
            "Flush interval must be positive: ${properties.processing.flushIntervalMs}"
        }
        require(properties.processing.largeMessageThreshold > 0) {
            "Large message threshold must be positive: ${properties.processing.largeMessageThreshold}"
        }
    }
}

/**
 * DLQ 기능 정보 로거
 */
class DLQFeatureLogger(
    private val properties: DLQConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    init {
        logFeatureStatus()
    }
    
    private fun logFeatureStatus() {
        logger.info("""
            
            ========================================
            Kafka DLQ Handler Configuration Summary
            ========================================
            Enabled: ${properties.enabled}
            Topic Pattern: ${properties.topicPattern}
            Processing:
              - Workers: ${if (properties.processing.workers == 0) "Auto" else properties.processing.workers}
              - Batch Size: ${properties.processing.batchSize}
              - Flush Interval: ${properties.processing.flushIntervalMs}ms
              - Large Message Threshold: ${properties.processing.largeMessageThreshold} bytes
              - Auto Optimize: ${properties.processing.autoOptimize}
            Storage:
              - External Storage: ${properties.storage.enableExternalStorage}
              - External Bucket: ${properties.storage.externalBucket}
            ========================================
            
        """.trimIndent())
    }
}