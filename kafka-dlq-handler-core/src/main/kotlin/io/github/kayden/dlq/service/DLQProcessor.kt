package io.github.kayden.dlq.service

import io.github.kayden.dlq.config.DLQConfigurationProperties
import io.github.kayden.dlq.domain.DLQMessageBatch
import io.github.kayden.dlq.domain.DLQMessageSummary
import io.github.kayden.dlq.domain.enums.DLQStatus
import io.github.kayden.dlq.domain.value.DLQMessageData
import io.github.kayden.dlq.repository.DLQMessageBatchRepository
import io.github.kayden.dlq.repository.DLQMessageSummaryRepository
import io.github.kayden.dlq.storage.PayloadStorageService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy

/**
 * DLQ 메시지 처리기
 * 
 * 트래픽에 따라 자동으로 최적화되는 프로세서.
 * 적은 트래픽에서는 단순 처리, 많은 트래픽에서는 배치 처리를 자동 적용한다.
 */
@Service
class DLQProcessor(
    private val summaryRepository: DLQMessageSummaryRepository,
    private val batchRepository: DLQMessageBatchRepository,
    private val payloadStorage: PayloadStorageService,
    private val properties: DLQConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    // 메트릭
    private val processedCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    
    // 코루틴 스코프
    private val processorScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob()
    )
    
    // 메시지 채널 (자동 크기 조정)
    private val channelCapacity = calculateOptimalChannelCapacity()
    private val messageChannel = Channel<ProcessingTask>(
        capacity = channelCapacity
    )
    
    // 배치 버퍼
    private val batchBuffers = mutableMapOf<String, MutableList<DLQMessageData>>()
    
    @PostConstruct
    fun init() {
        // 배치 처리 워커 시작 (자동 최적화)
        val workerCount = calculateOptimalWorkerCount()
        repeat(workerCount) { workerId ->
            processorScope.launch {
                batchProcessor(workerId)
            }
        }
        
        // 배치 플러셔 시작
        processorScope.launch {
            batchFlusher()
        }
        
        logger.info(
            "DLQ Processor started with {} workers",
            workerCount
        )
    }
    
    @PreDestroy
    fun destroy() {
        runBlocking {
            messageChannel.close()
            processorScope.cancel()
            flushAllBatches()
        }
    }
    
    /**
     * Kafka 레코드 처리 진입점
     */
    suspend fun processRecord(record: ConsumerRecord<String, String>) {
        val task = ProcessingTask(
            record = record,
            receivedAt = Instant.now()
        )
        
        // 논블로킹으로 채널에 전송
        messageChannel.send(task)
    }
    
    /**
     * 배치 처리 워커
     */
    private suspend fun batchProcessor(workerId: Int) {
        messageChannel.consumeAsFlow()
            .buffer(calculateOptimalBufferSize())
            .collect { task ->
                try {
                    processTask(task)
                    processedCount.incrementAndGet()
                } catch (e: Exception) {
                    logger.error("Worker $workerId failed to process task", e)
                    errorCount.incrementAndGet()
                }
            }
    }
    
    /**
     * 개별 태스크 처리
     */
    private suspend fun processTask(task: ProcessingTask) {
        val record = task.record
        val messageId = generateMessageId(record)
        
        // 1. 요약 정보 생성
        val summary = createSummary(messageId, record, task.receivedAt)
        
        // 2. 대용량 메시지 처리
        val payloadLocation = if (summary.isLargeMessage()) {
            // S3 등 외부 저장소에 저장
            payloadStorage.store(messageId, record.value())
        } else {
            null
        }
        
        // 3. 요약 정보 저장 (비동기)
        processorScope.launch {
            summaryRepository.save(
                summary.copy(payloadLocation = payloadLocation)
            )
        }
        
        // 4. 배치 버퍼에 추가
        if (!summary.isLargeMessage()) {
            addToBatch(record, task.receivedAt)
        }
    }
    
    /**
     * 배치 버퍼에 메시지 추가
     */
    private fun addToBatch(
        record: ConsumerRecord<String, String>,
        timestamp: Instant
    ) {
        val topic = record.topic()
        val messageData = DLQMessageData(
            messageKey = record.key(),
            payload = record.value(),
            headers = extractHeaders(record),
            errorClass = extractErrorClass(record),
            errorMessage = extractErrorMessage(record),
            timestamp = timestamp,
            partitionId = record.partition(),
            offsetValue = record.offset()
        )
        
        synchronized(batchBuffers) {
            val buffer = batchBuffers.computeIfAbsent(topic) { 
                mutableListOf() 
            }
            buffer.add(messageData)
            
            // 배치 크기 도달 시 즉시 플러시
            if (buffer.size >= properties.processing.batchSize) {
                flushBatch(topic, buffer.toList())
                buffer.clear()
            }
        }
    }
    
    /**
     * 배치 플러셔 (주기적으로 실행)
     */
    private suspend fun batchFlusher() {
        while (processorScope.isActive) {
            delay(properties.processing.flushIntervalMs)
            flushAllBatches()
        }
    }
    
    /**
     * 모든 배치 플러시
     */
    private fun flushAllBatches() {
        synchronized(batchBuffers) {
            batchBuffers.forEach { (topic, buffer) ->
                if (buffer.isNotEmpty()) {
                    flushBatch(topic, buffer.toList())
                    buffer.clear()
                }
            }
        }
    }
    
    /**
     * 배치 저장
     */
    private fun flushBatch(topic: String, messages: List<DLQMessageData>) {
        try {
            val batchId = UUID.randomUUID().toString()
            val compressedData = compressMessages(messages)
            val checksum = calculateChecksum(compressedData)
            
            val batch = DLQMessageBatch(
                batchId = batchId,
                topic = topic,
                messageCount = messages.size,
                totalSize = messages.sumOf { it.payload.length.toLong() },
                compressedData = compressedData,
                checksum = checksum
            )
            
            batchRepository.save(batch)
            
            logger.debug(
                "Flushed batch {} with {} messages for topic {}",
                batchId, messages.size, topic
            )
        } catch (e: Exception) {
            logger.error("Failed to flush batch for topic $topic", e)
        }
    }
    
    /**
     * 메시지 압축
     */
    private fun compressMessages(messages: List<DLQMessageData>): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            val json = objectMapper.writeValueAsString(messages)
            gzip.write(json.toByteArray())
        }
        return baos.toByteArray()
    }
    
    private fun generateMessageId(record: ConsumerRecord<String, String>): String {
        return "${record.topic()}-${record.partition()}-${record.offset()}"
    }
    
    private fun createSummary(
        messageId: String,
        record: ConsumerRecord<String, String>,
        receivedAt: Instant
    ): DLQMessageSummary {
        return DLQMessageSummary(
            id = messageId,
            originalTopic = extractOriginalTopic(record),
            partitionId = record.partition(),
            offsetValue = record.offset(),
            status = DLQStatus.PENDING,
            errorType = classifyError(record),
            messageSize = record.value().length,
            createdAt = receivedAt,
            payloadLocation = null
        )
    }
    
    // Helper 메서드들...
    private fun extractHeaders(record: ConsumerRecord<String, String>): Map<String, String> {
        return record.headers().associate { header ->
            header.key() to String(header.value())
        }
    }
    
    private fun extractOriginalTopic(record: ConsumerRecord<String, String>): String {
        return record.headers()
            .lastHeader("kafka_dlt-original-topic")
            ?.let { String(it.value()) }
            ?: record.topic()
    }
    
    private fun extractErrorClass(record: ConsumerRecord<String, String>): String? {
        return record.headers()
            .lastHeader("kafka_dlt-exception-fqcn")
            ?.let { String(it.value()) }
    }
    
    private fun extractErrorMessage(record: ConsumerRecord<String, String>): String? {
        return record.headers()
            .lastHeader("kafka_dlt-exception-message")
            ?.let { String(it.value()) }
    }
    
    private fun classifyError(record: ConsumerRecord<String, String>): String {
        val errorClass = extractErrorClass(record) ?: return "UNKNOWN"
        
        return when {
            errorClass.contains("Timeout") -> "TRANSIENT"
            errorClass.contains("Network") -> "TRANSIENT"
            errorClass.contains("Deserialization") -> "PERMANENT"
            errorClass.contains("Validation") -> "PERMANENT"
            else -> "UNKNOWN"
        }
    }
    
    private fun calculateChecksum(data: ByteArray): String {
        return data.contentHashCode().toString()
    }
    
    /**
     * 최적 워커 수 계산
     */
    private fun calculateOptimalWorkerCount(): Int {
        return if (properties.processing.workers > 0) {
            properties.processing.workers
        } else {
            // CPU 코어 수 기반 자동 설정
            val cores = Runtime.getRuntime().availableProcessors()
            maxOf(2, minOf(cores, 8)) // 2~8 사이
        }
    }
    
    /**
     * 최적 채널 용량 계산
     */
    private fun calculateOptimalChannelCapacity(): Int {
        // 메모리와 트래픽에 따라 자동 조정
        val maxMemory = Runtime.getRuntime().maxMemory()
        val memoryBasedCapacity = (maxMemory / (1024 * 1024 * 100)).toInt() * 1000 // 100MB당 1000개
        return minOf(memoryBasedCapacity, 50000) // 최대 5만개
    }
    
    /**
     * 최적 버퍼 크기 계산
     */
    private fun calculateOptimalBufferSize(): Int {
        // 배치 크기의 2배로 설정
        return properties.processing.batchSize * 2
    }
    
    companion object {
        private val objectMapper: ObjectMapper = jacksonObjectMapper()
        const val LARGE_MESSAGE_THRESHOLD = 102400 // 100KB
    }
}

/**
 * 처리 태스크
 */
data class ProcessingTask(
    val record: ConsumerRecord<String, String>,
    val receivedAt: Instant
)