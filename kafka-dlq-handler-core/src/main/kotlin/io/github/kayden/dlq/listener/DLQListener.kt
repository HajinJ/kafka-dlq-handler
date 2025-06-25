package io.github.kayden.dlq.listener

import io.github.kayden.dlq.config.DLQConfigurationProperties
import io.github.kayden.dlq.service.DLQProcessor
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * DLQ 메시지 리스너
 * 
 * 트래픽에 따라 단건 또는 배치 처리를 자동 선택한다.
 */
@Component
@ConditionalOnProperty(
    prefix = "kafka.dlq",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class DLQListener(
    private val processor: DLQProcessor,
    private val properties: DLQConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    @KafkaListener(
        topicPattern = "\${kafka.dlq.topic-pattern:.*\\.DLQ}",
        containerFactory = "dlqKafkaListenerContainerFactory"
    )
    fun handleBatch(
        records: List<ConsumerRecord<String, String>>,
        acknowledgment: Acknowledgment
    ) {
        val startTime = System.currentTimeMillis()
        
        try {
            // 코루틴으로 비동기 처리
            runBlocking {
                records.forEach { record ->
                    processor.processRecord(record)
                }
            }
            
            // 처리 완료 후 ACK
            acknowledgment.acknowledge()
            
            val duration = System.currentTimeMillis() - startTime
            logger.debug(
                "Processed batch of {} messages in {}ms",
                records.size, duration
            )
            
        } catch (e: Exception) {
            logger.error("Failed to process batch", e)
            // ACK하지 않으면 재처리됨
        }
    }
}