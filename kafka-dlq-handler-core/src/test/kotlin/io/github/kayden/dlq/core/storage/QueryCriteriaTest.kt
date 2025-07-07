package io.github.kayden.dlq.core.storage

import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class QueryCriteriaTest {
    
    @Test
    @DisplayName("유효한 파라미터로 QueryCriteria를 생성할 수 있다")
    fun shouldCreateQueryCriteriaWithValidParameters() {
        val criteria = QueryCriteria(
            statuses = setOf(DLQStatus.PENDING, DLQStatus.RETRYING),
            errorTypes = setOf(ErrorType.TRANSIENT_NETWORK_ERROR),
            topics = setOf("topic1", "topic2"),
            messageKeyPattern = "key-.*",
            errorMessagePattern = ".*timeout.*",
            createdAfter = 1000L,
            createdBefore = 2000L,
            retryCountMin = 1,
            retryCountMax = 5,
            sortBy = SortField.RETRY_COUNT,
            sortOrder = SortOrder.ASC,
            offset = 10,
            limit = 50
        )
        
        assertEquals(setOf(DLQStatus.PENDING, DLQStatus.RETRYING), criteria.statuses)
        assertEquals(setOf(ErrorType.TRANSIENT_NETWORK_ERROR), criteria.errorTypes)
        assertEquals(setOf("topic1", "topic2"), criteria.topics)
        assertEquals("key-.*", criteria.messageKeyPattern)
        assertEquals(".*timeout.*", criteria.errorMessagePattern)
        assertEquals(1000L, criteria.createdAfter)
        assertEquals(2000L, criteria.createdBefore)
        assertEquals(1, criteria.retryCountMin)
        assertEquals(5, criteria.retryCountMax)
        assertEquals(SortField.RETRY_COUNT, criteria.sortBy)
        assertEquals(SortOrder.ASC, criteria.sortOrder)
        assertEquals(10, criteria.offset)
        assertEquals(50, criteria.limit)
    }
    
    @Test
    @DisplayName("기본값으로 QueryCriteria를 생성할 수 있다")
    fun shouldCreateWithDefaults() {
        val criteria = QueryCriteria()
        
        assertNull(criteria.statuses)
        assertNull(criteria.errorTypes)
        assertNull(criteria.topics)
        assertEquals(SortField.CREATED_AT, criteria.sortBy)
        assertEquals(SortOrder.DESC, criteria.sortOrder)
        assertEquals(0, criteria.offset)
        assertEquals(100, criteria.limit)
    }
    
    @Test
    @DisplayName("유효하지 않은 파라미터로 생성할 수 없다")
    fun shouldValidateParameters() {
        // 음수 offset
        assertThrows<IllegalArgumentException> {
            QueryCriteria(offset = -1)
        }
        
        // 0 또는 음수 limit
        assertThrows<IllegalArgumentException> {
            QueryCriteria(limit = 0)
        }
        assertThrows<IllegalArgumentException> {
            QueryCriteria(limit = -1)
        }
        
        // 너무 큰 limit
        assertThrows<IllegalArgumentException> {
            QueryCriteria(limit = 10001)
        }
        
        // createdAfter > createdBefore
        assertThrows<IllegalArgumentException> {
            QueryCriteria(createdAfter = 2000L, createdBefore = 1000L)
        }
        
        // lastProcessedAfter > lastProcessedBefore
        assertThrows<IllegalArgumentException> {
            QueryCriteria(lastProcessedAfter = 2000L, lastProcessedBefore = 1000L)
        }
        
        // 음수 retryCountMin
        assertThrows<IllegalArgumentException> {
            QueryCriteria(retryCountMin = -1)
        }
        
        // retryCountMin > retryCountMax
        assertThrows<IllegalArgumentException> {
            QueryCriteria(retryCountMin = 5, retryCountMax = 3)
        }
        
        // 음수 payloadSizeMin
        assertThrows<IllegalArgumentException> {
            QueryCriteria(payloadSizeMin = -1)
        }
        
        // payloadSizeMin > payloadSizeMax
        assertThrows<IllegalArgumentException> {
            QueryCriteria(payloadSizeMin = 1000, payloadSizeMax = 500)
        }
    }
    
    @Test
    @DisplayName("재처리 대상 조회 조건을 생성할 수 있다")
    fun shouldCreateRetryableCriteria() {
        val criteria = QueryCriteria.retryable(limit = 200)
        
        assertEquals(setOf(DLQStatus.PENDING, DLQStatus.RETRYING), criteria.statuses)
        assertEquals(SortField.RETRY_COUNT, criteria.sortBy)
        assertEquals(SortOrder.ASC, criteria.sortOrder)
        assertEquals(200, criteria.limit)
    }
    
    @Test
    @DisplayName("실패한 레코드 조회 조건을 생성할 수 있다")
    fun shouldCreateFailedCriteria() {
        val currentTime = System.currentTimeMillis()
        val criteria = QueryCriteria.failed(since = 1.hours, limit = 50)
        
        assertEquals(setOf(DLQStatus.FAILED, DLQStatus.EXPIRED), criteria.statuses)
        assertNotNull(criteria.createdAfter)
        assertTrue(criteria.createdAfter!! >= currentTime - 1.hours.inWholeMilliseconds - 1000)
        assertEquals(SortField.CREATED_AT, criteria.sortBy)
        assertEquals(SortOrder.DESC, criteria.sortOrder)
        assertEquals(50, criteria.limit)
    }
    
    @Test
    @DisplayName("토픽별 조회 조건을 생성할 수 있다")
    fun shouldCreateTopicCriteria() {
        val criteria = QueryCriteria.byTopic("test-topic", limit = 150)
        
        assertEquals(setOf("test-topic"), criteria.topics)
        assertEquals(SortField.CREATED_AT, criteria.sortBy)
        assertEquals(SortOrder.DESC, criteria.sortOrder)
        assertEquals(150, criteria.limit)
    }
    
    @Test
    @DisplayName("에러 타입별 조회 조건을 생성할 수 있다")
    fun shouldCreateErrorTypeCriteria() {
        val criteria = QueryCriteria.byErrorType(ErrorType.TRANSIENT_NETWORK_ERROR, limit = 75)
        
        assertEquals(setOf(ErrorType.TRANSIENT_NETWORK_ERROR), criteria.errorTypes)
        assertEquals(SortField.CREATED_AT, criteria.sortBy)
        assertEquals(SortOrder.DESC, criteria.sortOrder)
        assertEquals(75, criteria.limit)
    }
    
    @Test
    @DisplayName("오래된 성공 레코드 조회 조건을 생성할 수 있다")
    fun shouldCreateOldSuccessfulCriteria() {
        val currentTime = System.currentTimeMillis()
        val criteria = QueryCriteria.oldSuccessful(olderThan = 3.days, limit = 500)
        
        assertEquals(setOf(DLQStatus.SUCCESS), criteria.statuses)
        assertNotNull(criteria.createdBefore)
        assertTrue(criteria.createdBefore!! <= currentTime - 3.days.inWholeMilliseconds + 1000)
        assertEquals(SortField.CREATED_AT, criteria.sortBy)
        assertEquals(SortOrder.ASC, criteria.sortOrder) // 오래된 것부터
        assertEquals(500, criteria.limit)
    }
    
    @Test
    @DisplayName("대용량 메시지 조회 조건을 생성할 수 있다")
    fun shouldCreateLargeMessagesCriteria() {
        val criteria = QueryCriteria.largeMessages(minSize = 2_000_000, limit = 25)
        
        assertEquals(2_000_000, criteria.payloadSizeMin)
        assertEquals(SortField.PAYLOAD_SIZE, criteria.sortBy)
        assertEquals(SortOrder.DESC, criteria.sortOrder)
        assertEquals(25, criteria.limit)
    }
    
    @Test
    @DisplayName("QueryCriteriaBuilder로 복잡한 조건을 생성할 수 있다")
    fun shouldBuildComplexCriteria() {
        val currentTime = System.currentTimeMillis()
        
        val criteria = QueryCriteria.builder()
            .withStatuses(DLQStatus.PENDING, DLQStatus.RETRYING)
            .withErrorTypes(ErrorType.TRANSIENT_NETWORK_ERROR, ErrorType.TRANSIENT_SERVICE_ERROR)
            .withTopics("topic1", "topic2")
            .withMessageKeyPattern("user-\\d+")
            .withErrorMessagePattern(".*timeout.*")
            .createdWithinLast(1.hours)
            .lastProcessedWithinLast(30.minutes)
            .withRetryCountBetween(1, 5)
            .withPayloadSizeBetween(1000, 10000)
            .sortBy(SortField.RETRY_COUNT, SortOrder.ASC)
            .withPagination(20, 50)
            .build()
        
        assertEquals(setOf(DLQStatus.PENDING, DLQStatus.RETRYING), criteria.statuses)
        assertEquals(setOf(ErrorType.TRANSIENT_NETWORK_ERROR, ErrorType.TRANSIENT_SERVICE_ERROR), criteria.errorTypes)
        assertEquals(setOf("topic1", "topic2"), criteria.topics)
        assertEquals("user-\\d+", criteria.messageKeyPattern)
        assertEquals(".*timeout.*", criteria.errorMessagePattern)
        assertNotNull(criteria.createdAfter)
        assertTrue(criteria.createdAfter!! >= currentTime - 1.hours.inWholeMilliseconds - 1000)
        assertNotNull(criteria.lastProcessedAfter)
        assertTrue(criteria.lastProcessedAfter!! >= currentTime - 30.minutes.inWholeMilliseconds - 1000)
        assertEquals(1, criteria.retryCountMin)
        assertEquals(5, criteria.retryCountMax)
        assertEquals(1000, criteria.payloadSizeMin)
        assertEquals(10000, criteria.payloadSizeMax)
        assertEquals(SortField.RETRY_COUNT, criteria.sortBy)
        assertEquals(SortOrder.ASC, criteria.sortOrder)
        assertEquals(20, criteria.offset)
        assertEquals(50, criteria.limit)
    }
    
    @Test
    @DisplayName("QueryCriteria DSL로 조건을 생성할 수 있다")
    fun shouldCreateCriteriaWithDSL() {
        val criteria = queryCriteria {
            withStatuses(DLQStatus.FAILED)
            withErrorTypes(ErrorType.PERMANENT_FAILURE)
            withTopics("dead-letter-topic")
            withRetryCountAtLeast(3)
            withPayloadSizeAtMost(5000)
            sortBy(SortField.CREATED_AT)
            withLimit(25)
        }
        
        assertEquals(setOf(DLQStatus.FAILED), criteria.statuses)
        assertEquals(setOf(ErrorType.PERMANENT_FAILURE), criteria.errorTypes)
        assertEquals(setOf("dead-letter-topic"), criteria.topics)
        assertEquals(3, criteria.retryCountMin)
        assertNull(criteria.retryCountMax)
        assertNull(criteria.payloadSizeMin)
        assertEquals(5000, criteria.payloadSizeMax)
        assertEquals(SortField.CREATED_AT, criteria.sortBy)
        assertEquals(SortOrder.DESC, criteria.sortOrder)
        assertEquals(25, criteria.limit)
    }
    
    @Test
    @DisplayName("빌더로 개별 제약 조건을 설정할 수 있다")
    fun shouldSetIndividualConstraints() {
        val builder = QueryCriteria.builder()
        
        // 최소 재시도 횟수만 설정
        val minRetryOnly = builder.withRetryCountAtLeast(2).build()
        assertEquals(2, minRetryOnly.retryCountMin)
        assertNull(minRetryOnly.retryCountMax)
        
        // 최대 재시도 횟수만 설정
        val maxRetryOnly = QueryCriteria.builder().withRetryCountAtMost(10).build()
        assertNull(maxRetryOnly.retryCountMin)
        assertEquals(10, maxRetryOnly.retryCountMax)
        
        // 최소 페이로드 크기만 설정
        val minSizeOnly = QueryCriteria.builder().withPayloadSizeAtLeast(1000).build()
        assertEquals(1000, minSizeOnly.payloadSizeMin)
        assertNull(minSizeOnly.payloadSizeMax)
        
        // 최대 페이로드 크기만 설정
        val maxSizeOnly = QueryCriteria.builder().withPayloadSizeAtMost(10000).build()
        assertNull(maxSizeOnly.payloadSizeMin)
        assertEquals(10000, maxSizeOnly.payloadSizeMax)
    }
    
    @Test
    @DisplayName("여러 상태와 에러 타입을 누적할 수 있다")
    fun shouldAccumulateMultipleValues() {
        val criteria = QueryCriteria.builder()
            .withStatuses(DLQStatus.PENDING)
            .withStatuses(DLQStatus.RETRYING, DLQStatus.FAILED)
            .withErrorTypes(ErrorType.TRANSIENT_NETWORK_ERROR)
            .withErrorTypes(ErrorType.TRANSIENT_SERVICE_ERROR)
            .withTopics("topic1")
            .withTopics("topic2", "topic3")
            .build()
        
        assertEquals(
            setOf(DLQStatus.PENDING, DLQStatus.RETRYING, DLQStatus.FAILED),
            criteria.statuses
        )
        assertEquals(
            setOf(ErrorType.TRANSIENT_NETWORK_ERROR, ErrorType.TRANSIENT_SERVICE_ERROR),
            criteria.errorTypes
        )
        assertEquals(
            setOf("topic1", "topic2", "topic3"),
            criteria.topics
        )
    }
}