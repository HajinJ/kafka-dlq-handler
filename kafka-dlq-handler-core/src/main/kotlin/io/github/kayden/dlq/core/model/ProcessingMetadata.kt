package io.github.kayden.dlq.core.model

/**
 * DLQ 메시지 처리와 관련된 메타데이터를 담는 value object.
 * 
 * 이 클래스는 불변 객체로 설계되어 thread-safe하며,
 * 메시지 처리 과정에서 발생하는 다양한 정보를 추적한다.
 * 
 * @property processedBy 메시지를 처리한 프로세서/인스턴스 식별자
 * @property processingDurationMillis 처리에 소요된 시간 (밀리초)
 * @property batchId 배치 처리 시 배치 식별자
 * @property attemptNumber 현재 처리 시도 번호
 * @property nextRetryAt 다음 재시도 예정 시간 (epoch milliseconds)
 * @property backoffDelayMillis 백오프 지연 시간 (밀리초)
 * @property tags 추가 메타데이터를 위한 태그 맵
 * @property metrics 처리 관련 메트릭 정보
 * 
 * @since 0.1.0
 */
data class ProcessingMetadata(
    val processedBy: String? = null,
    val processingDurationMillis: Long? = null,
    val batchId: String? = null,
    val attemptNumber: Int = 1,
    val nextRetryAt: Long? = null,
    val backoffDelayMillis: Long? = null,
    val tags: Map<String, String> = emptyMap(),
    val metrics: ProcessingMetrics? = null
) {
    init {
        require(attemptNumber > 0) { "Attempt number must be positive" }
        processingDurationMillis?.let {
            require(it >= 0) { "Processing duration must not be negative" }
        }
        backoffDelayMillis?.let {
            require(it >= 0) { "Backoff delay must not be negative" }
        }
    }
    
    /**
     * 태그를 추가한 새로운 인스턴스를 생성한다.
     * 
     * @param key 태그 키
     * @param value 태그 값
     * @return 태그가 추가된 새 인스턴스
     */
    fun withTag(key: String, value: String): ProcessingMetadata =
        copy(tags = tags + (key to value))
    
    /**
     * 여러 태그를 추가한 새로운 인스턴스를 생성한다.
     * 
     * @param newTags 추가할 태그 맵
     * @return 태그가 추가된 새 인스턴스
     */
    fun withTags(newTags: Map<String, String>): ProcessingMetadata =
        copy(tags = tags + newTags)
    
    /**
     * 처리 시간을 기록한 새로운 인스턴스를 생성한다.
     * 
     * @param startTimeMillis 처리 시작 시간
     * @param endTimeMillis 처리 종료 시간
     * @return 처리 시간이 기록된 새 인스턴스
     */
    fun withProcessingTime(startTimeMillis: Long, endTimeMillis: Long): ProcessingMetadata {
        require(endTimeMillis >= startTimeMillis) { "End time must be after start time" }
        return copy(processingDurationMillis = endTimeMillis - startTimeMillis)
    }
    
    /**
     * 다음 재시도 정보를 설정한 새로운 인스턴스를 생성한다.
     * 
     * @param nextRetryTime 다음 재시도 시간
     * @param backoffDelay 백오프 지연 시간
     * @return 재시도 정보가 설정된 새 인스턴스
     */
    fun withRetryInfo(nextRetryTime: Long, backoffDelay: Long): ProcessingMetadata =
        copy(
            nextRetryAt = nextRetryTime,
            backoffDelayMillis = backoffDelay,
            attemptNumber = attemptNumber + 1
        )
    
    /**
     * 메트릭을 업데이트한 새로운 인스턴스를 생성한다.
     * 
     * @param metrics 새로운 메트릭 정보
     * @return 메트릭이 업데이트된 새 인스턴스
     */
    fun withMetrics(metrics: ProcessingMetrics): ProcessingMetadata =
        copy(metrics = metrics)
    
    /**
     * 처리가 성공했는지 확인한다.
     * 
     * @return 처리 시간이 기록되어 있으면 true
     */
    fun isProcessed(): Boolean = processingDurationMillis != null
    
    /**
     * 재시도가 예정되어 있는지 확인한다.
     * 
     * @return 다음 재시도 시간이 설정되어 있으면 true
     */
    fun isRetryScheduled(): Boolean = nextRetryAt != null
    
    companion object {
        /**
         * 빈 메타데이터 인스턴스.
         */
        val EMPTY = ProcessingMetadata()
        
        /**
         * 초기 처리를 위한 메타데이터를 생성한다.
         * 
         * @param processedBy 프로세서 식별자
         * @param batchId 배치 식별자 (optional)
         * @return 초기 처리 메타데이터
         */
        fun initial(processedBy: String, batchId: String? = null): ProcessingMetadata =
            ProcessingMetadata(
                processedBy = processedBy,
                batchId = batchId,
                attemptNumber = 1
            )
    }
}

/**
 * 처리 관련 메트릭 정보를 담는 data class.
 * 
 * @property messagesProcessed 처리된 메시지 수
 * @property messagesSuccess 성공한 메시지 수
 * @property messagesFailed 실패한 메시지 수
 * @property bytesProcessed 처리된 바이트 수
 * @property throughputMessagesPerSecond 초당 메시지 처리량
 * @property averageProcessingTimeMillis 평균 처리 시간 (밀리초)
 * 
 * @since 0.1.0
 */
data class ProcessingMetrics(
    val messagesProcessed: Long = 0,
    val messagesSuccess: Long = 0,
    val messagesFailed: Long = 0,
    val bytesProcessed: Long = 0,
    val throughputMessagesPerSecond: Double? = null,
    val averageProcessingTimeMillis: Double? = null
) {
    init {
        require(messagesProcessed >= 0) { "Messages processed must not be negative" }
        require(messagesSuccess >= 0) { "Messages success must not be negative" }
        require(messagesFailed >= 0) { "Messages failed must not be negative" }
        require(bytesProcessed >= 0) { "Bytes processed must not be negative" }
        require(messagesSuccess + messagesFailed <= messagesProcessed) {
            "Success + Failed messages cannot exceed total processed"
        }
        throughputMessagesPerSecond?.let {
            require(it >= 0) { "Throughput must not be negative" }
        }
        averageProcessingTimeMillis?.let {
            require(it >= 0) { "Average processing time must not be negative" }
        }
    }
    
    /**
     * 성공률을 계산한다.
     * 
     * @return 성공률 (0.0 ~ 1.0), 처리된 메시지가 없으면 null
     */
    fun successRate(): Double? {
        return if (messagesProcessed > 0) {
            messagesSuccess.toDouble() / messagesProcessed
        } else {
            null
        }
    }
    
    /**
     * 실패율을 계산한다.
     * 
     * @return 실패율 (0.0 ~ 1.0), 처리된 메시지가 없으면 null
     */
    fun failureRate(): Double? {
        return if (messagesProcessed > 0) {
            messagesFailed.toDouble() / messagesProcessed
        } else {
            null
        }
    }
    
    /**
     * 메시지당 평균 바이트 수를 계산한다.
     * 
     * @return 메시지당 평균 바이트 수, 처리된 메시지가 없으면 null
     */
    fun averageBytesPerMessage(): Double? {
        return if (messagesProcessed > 0) {
            bytesProcessed.toDouble() / messagesProcessed
        } else {
            null
        }
    }
    
    companion object {
        /**
         * 빈 메트릭 인스턴스.
         */
        val EMPTY = ProcessingMetrics()
        
        /**
         * 여러 메트릭을 병합한다.
         * 
         * @param metrics 병합할 메트릭들
         * @return 병합된 메트릭
         */
        fun merge(vararg metrics: ProcessingMetrics): ProcessingMetrics {
            if (metrics.isEmpty()) return EMPTY
            
            val totalProcessed = metrics.sumOf { it.messagesProcessed }
            val totalSuccess = metrics.sumOf { it.messagesSuccess }
            val totalFailed = metrics.sumOf { it.messagesFailed }
            val totalBytes = metrics.sumOf { it.bytesProcessed }
            
            val avgProcessingTime = metrics
                .mapNotNull { it.averageProcessingTimeMillis }
                .takeIf { it.isNotEmpty() }
                ?.average()
            
            val avgThroughput = metrics
                .mapNotNull { it.throughputMessagesPerSecond }
                .takeIf { it.isNotEmpty() }
                ?.average()
            
            return ProcessingMetrics(
                messagesProcessed = totalProcessed,
                messagesSuccess = totalSuccess,
                messagesFailed = totalFailed,
                bytesProcessed = totalBytes,
                throughputMessagesPerSecond = avgThroughput,
                averageProcessingTimeMillis = avgProcessingTime
            )
        }
    }
}