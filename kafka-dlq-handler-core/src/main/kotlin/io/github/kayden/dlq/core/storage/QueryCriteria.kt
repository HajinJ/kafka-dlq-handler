package io.github.kayden.dlq.core.storage

import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * DLQ 레코드 조회 조건을 정의하는 data class.
 * 
 * 다양한 조건을 조합하여 복잡한 쿼리를 구성할 수 있다.
 * 모든 조건은 AND로 결합된다.
 * 
 * @property statuses 조회할 상태 목록
 * @property errorTypes 조회할 에러 타입 목록
 * @property topics 조회할 원본 토픽 목록
 * @property messageKeyPattern 메시지 키 패턴 (정규식)
 * @property errorMessagePattern 에러 메시지 패턴 (정규식)
 * @property createdAfter 이 시간 이후에 생성된 레코드
 * @property createdBefore 이 시간 이전에 생성된 레코드
 * @property lastProcessedAfter 이 시간 이후에 마지막 처리된 레코드
 * @property lastProcessedBefore 이 시간 이전에 마지막 처리된 레코드
 * @property retryCountMin 최소 재시도 횟수
 * @property retryCountMax 최대 재시도 횟수
 * @property payloadSizeMin 최소 페이로드 크기 (bytes)
 * @property payloadSizeMax 최대 페이로드 크기 (bytes)
 * @property sortBy 정렬 기준
 * @property sortOrder 정렬 순서
 * @property offset 조회 시작 위치
 * @property limit 최대 조회 개수
 * 
 * @since 0.1.0
 */
data class QueryCriteria(
    val statuses: Set<DLQStatus>? = null,
    val errorTypes: Set<ErrorType>? = null,
    val topics: Set<String>? = null,
    val messageKeyPattern: String? = null,
    val errorMessagePattern: String? = null,
    val createdAfter: Long? = null,
    val createdBefore: Long? = null,
    val lastProcessedAfter: Long? = null,
    val lastProcessedBefore: Long? = null,
    val retryCountMin: Int? = null,
    val retryCountMax: Int? = null,
    val payloadSizeMin: Int? = null,
    val payloadSizeMax: Int? = null,
    val sortBy: SortField = SortField.CREATED_AT,
    val sortOrder: SortOrder = SortOrder.DESC,
    val offset: Int = 0,
    val limit: Int = 100
) {
    init {
        require(offset >= 0) { "Offset must not be negative" }
        require(limit > 0) { "Limit must be positive" }
        require(limit <= 10000) { "Limit must not exceed 10000" }
        
        createdAfter?.let { after ->
            createdBefore?.let { before ->
                require(after < before) { "Created after must be before created before" }
            }
        }
        
        lastProcessedAfter?.let { after ->
            lastProcessedBefore?.let { before ->
                require(after < before) { "Last processed after must be before last processed before" }
            }
        }
        
        retryCountMin?.let { min ->
            require(min >= 0) { "Retry count min must not be negative" }
            retryCountMax?.let { max ->
                require(min <= max) { "Retry count min must not exceed max" }
            }
        }
        
        payloadSizeMin?.let { min ->
            require(min >= 0) { "Payload size min must not be negative" }
            payloadSizeMax?.let { max ->
                require(min <= max) { "Payload size min must not exceed max" }
            }
        }
    }
    
    companion object {
        /**
         * 재처리 대상 레코드를 조회하는 조건.
         */
        fun retryable(limit: Int = 100): QueryCriteria = QueryCriteria(
            statuses = setOf(DLQStatus.PENDING, DLQStatus.RETRYING),
            sortBy = SortField.RETRY_COUNT,
            sortOrder = SortOrder.ASC,
            limit = limit
        )
        
        /**
         * 실패한 레코드를 조회하는 조건.
         */
        fun failed(since: Duration? = null, limit: Int = 100): QueryCriteria = QueryCriteria(
            statuses = setOf(DLQStatus.FAILED, DLQStatus.EXPIRED),
            createdAfter = since?.let { System.currentTimeMillis() - it.inWholeMilliseconds },
            sortBy = SortField.CREATED_AT,
            sortOrder = SortOrder.DESC,
            limit = limit
        )
        
        /**
         * 특정 토픽의 레코드를 조회하는 조건.
         */
        fun byTopic(topic: String, limit: Int = 100): QueryCriteria = QueryCriteria(
            topics = setOf(topic),
            sortBy = SortField.CREATED_AT,
            sortOrder = SortOrder.DESC,
            limit = limit
        )
        
        /**
         * 특정 에러 타입의 레코드를 조회하는 조건.
         */
        fun byErrorType(errorType: ErrorType, limit: Int = 100): QueryCriteria = QueryCriteria(
            errorTypes = setOf(errorType),
            sortBy = SortField.CREATED_AT,
            sortOrder = SortOrder.DESC,
            limit = limit
        )
        
        /**
         * 오래된 성공 레코드를 조회하는 조건 (정리 대상).
         */
        fun oldSuccessful(olderThan: Duration = 7.days, limit: Int = 1000): QueryCriteria = QueryCriteria(
            statuses = setOf(DLQStatus.SUCCESS),
            createdBefore = System.currentTimeMillis() - olderThan.inWholeMilliseconds,
            sortBy = SortField.CREATED_AT,
            sortOrder = SortOrder.ASC,
            limit = limit
        )
        
        /**
         * 대용량 메시지를 조회하는 조건.
         */
        fun largeMessages(minSize: Int = 1_000_000, limit: Int = 100): QueryCriteria = QueryCriteria(
            payloadSizeMin = minSize,
            sortBy = SortField.PAYLOAD_SIZE,
            sortOrder = SortOrder.DESC,
            limit = limit
        )
        
        /**
         * 빌더 DSL을 시작한다.
         */
        fun builder(): QueryCriteriaBuilder = QueryCriteriaBuilder()
    }
}

/**
 * 정렬 필드를 정의하는 열거형.
 */
enum class SortField {
    CREATED_AT,
    LAST_PROCESSED_AT,
    RETRY_COUNT,
    PAYLOAD_SIZE,
    MESSAGE_KEY,
    ERROR_TYPE
}

/**
 * 정렬 순서를 정의하는 열거형.
 */
enum class SortOrder {
    ASC,
    DESC
}

/**
 * QueryCriteria를 구성하기 위한 빌더 클래스.
 */
class QueryCriteriaBuilder {
    private var statuses: MutableSet<DLQStatus>? = null
    private var errorTypes: MutableSet<ErrorType>? = null
    private var topics: MutableSet<String>? = null
    private var messageKeyPattern: String? = null
    private var errorMessagePattern: String? = null
    private var createdAfter: Long? = null
    private var createdBefore: Long? = null
    private var lastProcessedAfter: Long? = null
    private var lastProcessedBefore: Long? = null
    private var retryCountMin: Int? = null
    private var retryCountMax: Int? = null
    private var payloadSizeMin: Int? = null
    private var payloadSizeMax: Int? = null
    private var sortBy: SortField = SortField.CREATED_AT
    private var sortOrder: SortOrder = SortOrder.DESC
    private var offset: Int = 0
    private var limit: Int = 100
    
    fun withStatuses(vararg status: DLQStatus) = apply {
        if (statuses == null) statuses = mutableSetOf()
        statuses!!.addAll(status)
    }
    
    fun withErrorTypes(vararg errorType: ErrorType) = apply {
        if (errorTypes == null) errorTypes = mutableSetOf()
        errorTypes!!.addAll(errorType)
    }
    
    fun withTopics(vararg topic: String) = apply {
        if (topics == null) topics = mutableSetOf()
        topics!!.addAll(topic)
    }
    
    fun withMessageKeyPattern(pattern: String) = apply {
        messageKeyPattern = pattern
    }
    
    fun withErrorMessagePattern(pattern: String) = apply {
        errorMessagePattern = pattern
    }
    
    fun createdAfter(timestamp: Long) = apply {
        createdAfter = timestamp
    }
    
    fun createdBefore(timestamp: Long) = apply {
        createdBefore = timestamp
    }
    
    fun createdWithinLast(duration: Duration) = apply {
        createdAfter = System.currentTimeMillis() - duration.inWholeMilliseconds
    }
    
    fun lastProcessedAfter(timestamp: Long) = apply {
        lastProcessedAfter = timestamp
    }
    
    fun lastProcessedBefore(timestamp: Long) = apply {
        lastProcessedBefore = timestamp
    }
    
    fun lastProcessedWithinLast(duration: Duration) = apply {
        lastProcessedAfter = System.currentTimeMillis() - duration.inWholeMilliseconds
    }
    
    fun withRetryCountBetween(min: Int, max: Int) = apply {
        retryCountMin = min
        retryCountMax = max
    }
    
    fun withRetryCountAtLeast(min: Int) = apply {
        retryCountMin = min
    }
    
    fun withRetryCountAtMost(max: Int) = apply {
        retryCountMax = max
    }
    
    fun withPayloadSizeBetween(min: Int, max: Int) = apply {
        payloadSizeMin = min
        payloadSizeMax = max
    }
    
    fun withPayloadSizeAtLeast(min: Int) = apply {
        payloadSizeMin = min
    }
    
    fun withPayloadSizeAtMost(max: Int) = apply {
        payloadSizeMax = max
    }
    
    fun sortBy(field: SortField, order: SortOrder = SortOrder.DESC) = apply {
        sortBy = field
        sortOrder = order
    }
    
    fun withPagination(offset: Int, limit: Int) = apply {
        this.offset = offset
        this.limit = limit
    }
    
    fun withLimit(limit: Int) = apply {
        this.limit = limit
    }
    
    fun build(): QueryCriteria = QueryCriteria(
        statuses = statuses?.toSet(),
        errorTypes = errorTypes?.toSet(),
        topics = topics?.toSet(),
        messageKeyPattern = messageKeyPattern,
        errorMessagePattern = errorMessagePattern,
        createdAfter = createdAfter,
        createdBefore = createdBefore,
        lastProcessedAfter = lastProcessedAfter,
        lastProcessedBefore = lastProcessedBefore,
        retryCountMin = retryCountMin,
        retryCountMax = retryCountMax,
        payloadSizeMin = payloadSizeMin,
        payloadSizeMax = payloadSizeMax,
        sortBy = sortBy,
        sortOrder = sortOrder,
        offset = offset,
        limit = limit
    )
}

/**
 * QueryCriteria 빌더 DSL 함수.
 */
inline fun queryCriteria(block: QueryCriteriaBuilder.() -> Unit): QueryCriteria {
    return QueryCriteriaBuilder().apply(block).build()
}