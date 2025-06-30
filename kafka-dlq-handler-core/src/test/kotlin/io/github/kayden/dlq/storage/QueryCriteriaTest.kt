package io.github.kayden.dlq.storage

import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("QueryCriteria 테스트")
class QueryCriteriaTest {
    
    @Test
    @DisplayName("기본값으로 QueryCriteria 생성")
    fun createWithDefaults() {
        val criteria = QueryCriteria()
        
        assertThat(criteria.statuses).isNull()
        assertThat(criteria.errorTypes).isNull()
        assertThat(criteria.originalTopic).isNull()
        assertThat(criteria.createdAfter).isNull()
        assertThat(criteria.createdBefore).isNull()
        assertThat(criteria.retryCountLessThan).isNull()
        assertThat(criteria.retryCountGreaterThan).isNull()
        assertThat(criteria.limit).isEqualTo(100)
        assertThat(criteria.offset).isEqualTo(0)
        assertThat(criteria.sortBy).isEqualTo(SortField.CREATED_AT)
        assertThat(criteria.sortOrder).isEqualTo(SortOrder.DESC)
    }
    
    @Test
    @DisplayName("모든 필드를 지정하여 QueryCriteria 생성")
    fun createWithAllFields() {
        val now = Instant.now()
        val later = now.plusSeconds(3600)
        
        val criteria = QueryCriteria(
            statuses = setOf(DLQStatus.PENDING, DLQStatus.RETRYING),
            errorTypes = setOf(ErrorType.TRANSIENT, ErrorType.TIMEOUT),
            originalTopic = "orders",
            createdAfter = now,
            createdBefore = later,
            retryCountLessThan = 5,
            retryCountGreaterThan = 1,
            limit = 50,
            offset = 10,
            sortBy = SortField.RETRY_COUNT,
            sortOrder = SortOrder.ASC
        )
        
        assertThat(criteria.statuses).containsExactlyInAnyOrder(DLQStatus.PENDING, DLQStatus.RETRYING)
        assertThat(criteria.errorTypes).containsExactlyInAnyOrder(ErrorType.TRANSIENT, ErrorType.TIMEOUT)
        assertThat(criteria.originalTopic).isEqualTo("orders")
        assertThat(criteria.createdAfter).isEqualTo(now)
        assertThat(criteria.createdBefore).isEqualTo(later)
        assertThat(criteria.retryCountLessThan).isEqualTo(5)
        assertThat(criteria.retryCountGreaterThan).isEqualTo(1)
        assertThat(criteria.limit).isEqualTo(50)
        assertThat(criteria.offset).isEqualTo(10)
        assertThat(criteria.sortBy).isEqualTo(SortField.RETRY_COUNT)
        assertThat(criteria.sortOrder).isEqualTo(SortOrder.ASC)
    }
    
    @Test
    @DisplayName("limit이 0 이하인 경우 예외 발생")
    fun throwsExceptionWhenLimitIsZeroOrNegative() {
        assertThatThrownBy { QueryCriteria(limit = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Limit must be positive")
        
        assertThatThrownBy { QueryCriteria(limit = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Limit must be positive")
    }
    
    @Test
    @DisplayName("limit이 최대값을 초과하는 경우 예외 발생")
    fun throwsExceptionWhenLimitExceedsMax() {
        assertThatThrownBy { QueryCriteria(limit = QueryCriteria.MAX_LIMIT + 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Limit cannot exceed ${QueryCriteria.MAX_LIMIT}")
    }
    
    @Test
    @DisplayName("offset이 음수인 경우 예외 발생")
    fun throwsExceptionWhenOffsetIsNegative() {
        assertThatThrownBy { QueryCriteria(offset = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Offset must be non-negative")
    }
    
    @Test
    @DisplayName("retryCount 조건이 음수인 경우 예외 발생")
    fun throwsExceptionWhenRetryCountConditionsAreNegative() {
        assertThatThrownBy { QueryCriteria(retryCountLessThan = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("retryCountLessThan must be non-negative")
        
        assertThatThrownBy { QueryCriteria(retryCountGreaterThan = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("retryCountGreaterThan must be non-negative")
    }
    
    @Test
    @DisplayName("createdAfter가 createdBefore보다 늦은 경우 예외 발생")
    fun throwsExceptionWhenCreatedAfterIsAfterCreatedBefore() {
        val now = Instant.now()
        val earlier = now.minusSeconds(3600)
        
        assertThatThrownBy { 
            QueryCriteria(createdAfter = now, createdBefore = earlier) 
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("createdAfter must be before createdBefore")
    }
    
    @Test
    @DisplayName("pending() 팩토리 메서드 테스트")
    fun pendingFactoryMethod() {
        val criteria = QueryCriteria.pending()
        
        assertThat(criteria.statuses).containsExactly(DLQStatus.PENDING)
        assertThat(criteria.limit).isEqualTo(100)
        
        val criteriaWithLimit = QueryCriteria.pending(limit = 50)
        assertThat(criteriaWithLimit.limit).isEqualTo(50)
    }
    
    @Test
    @DisplayName("retryable() 팩토리 메서드 테스트")
    fun retryableFactoryMethod() {
        val criteria = QueryCriteria.retryable()
        
        assertThat(criteria.statuses).containsExactlyInAnyOrder(DLQStatus.PENDING, DLQStatus.RETRYING)
        assertThat(criteria.retryCountLessThan).isEqualTo(3)
        assertThat(criteria.limit).isEqualTo(100)
        
        val criteriaWithParams = QueryCriteria.retryable(maxRetries = 5, limit = 200)
        assertThat(criteriaWithParams.retryCountLessThan).isEqualTo(5)
        assertThat(criteriaWithParams.limit).isEqualTo(200)
    }
    
    @Test
    @DisplayName("failedByTopic() 팩토리 메서드 테스트")
    fun failedByTopicFactoryMethod() {
        val criteria = QueryCriteria.failedByTopic("orders")
        
        assertThat(criteria.originalTopic).isEqualTo("orders")
        assertThat(criteria.statuses).containsExactly(DLQStatus.FAILED)
        assertThat(criteria.limit).isEqualTo(100)
        
        val criteriaWithLimit = QueryCriteria.failedByTopic("payments", limit = 50)
        assertThat(criteriaWithLimit.originalTopic).isEqualTo("payments")
        assertThat(criteriaWithLimit.limit).isEqualTo(50)
    }
}