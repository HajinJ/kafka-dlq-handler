package io.github.kayden.dlq.config

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties

/**
 * DLQ Kafka 설정
 * 
 * 트래픽에 따라 자동 최적화되는 컨슈머 설정
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(
    prefix = "kafka.dlq",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class DLQKafkaConfig(
    private val properties: DLQConfigurationProperties
) {
    
    @Bean
    fun dlqConsumerFactory(): ConsumerFactory<String, String> {
        val configs = mutableMapOf<String, Any>()
        
        // 기본 설정
        configs[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        configs[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        
        // 성능 최적화 설정
        configs[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = 5000  // 한 번에 가져올 최대 레코드
        configs[ConsumerConfig.FETCH_MIN_BYTES_CONFIG] = 1024 * 1024  // 1MB
        configs[ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG] = 100  // 100ms
        configs[ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG] = 30000  // 30초
        configs[ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG] = 3000  // 3초
        
        // 병렬 처리를 위한 파티션 할당
        configs[ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG] = 
            "org.apache.kafka.clients.consumer.CooperativeStickyAssignor"
        
        return DefaultKafkaConsumerFactory(configs)
    }
    
    @Bean
    fun dlqKafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        
        factory.consumerFactory = dlqConsumerFactory()
        
        // 배치 리스너 활성화
        factory.isBatchListener = true
        
        // 동시성 설정 (파티션 수에 맞춰 자동 조정)
        val concurrency = if (properties.processing.workers > 0) {
            properties.processing.workers
        } else {
            Runtime.getRuntime().availableProcessors()
        }
        factory.setConcurrency(concurrency)
        
        // 수동 ACK 모드
        factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL
        
        // 폴링 타임아웃
        factory.containerProperties.pollTimeout = 1000
        
        // 아이들 이벤트 간격
        factory.containerProperties.idleEventInterval = 60000
        
        return factory
    }
}