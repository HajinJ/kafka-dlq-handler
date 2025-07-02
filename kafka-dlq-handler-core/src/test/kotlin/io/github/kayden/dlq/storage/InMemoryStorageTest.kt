package io.github.kayden.dlq.storage

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@DisplayName("InMemoryStorage 테스트")
class InMemoryStorageTest {
    
    private lateinit var storage: InMemoryStorage
    
    @BeforeEach
    fun setUp() {
        storage = InMemoryStorage()
    }
    
    @Test
    @DisplayName("단일 레코드 저장 및 조회")
    fun saveAndFindById() = runBlocking {
        val record = createTestRecord()
        
        val result = storage.save(record)
        assertThat(result).isInstanceOf(StorageResult.Success::class.java)
        assertThat((result as StorageResult.Success).id).isEqualTo(record.id)
        
        val found = storage.findById(record.id)
        assertThat(found).isNotNull
        assertThat(found).isEqualTo(record)
    }
    
    @Test
    @DisplayName("존재하지 않는 레코드 조회 시 null 반환")
    fun findByIdNotFound() = runBlocking {
        val found = storage.findById("non-existent-id")
        assertThat(found).isNull()
    }
    
    @Test
    @DisplayName("레코드 삭제")
    fun deleteById() = runBlocking {
        val record = createTestRecord()
        storage.save(record)
        
        val deleted = storage.deleteById(record.id)
        assertThat(deleted).isTrue
        
        val found = storage.findById(record.id)
        assertThat(found).isNull()
        
        val deletedAgain = storage.deleteById(record.id)
        assertThat(deletedAgain).isFalse
    }
    
    @Test
    @DisplayName("레코드 업데이트")
    fun updateRecord() = runBlocking {
        val record = createTestRecord()
        storage.save(record)
        
        val updatedRecord = record.copy(status = DLQStatus.SUCCESS)
        val result = storage.update(updatedRecord)
        
        assertThat(result).isInstanceOf(StorageResult.Success::class.java)
        
        val found = storage.findById(record.id)
        assertThat(found?.status).isEqualTo(DLQStatus.SUCCESS)
        assertThat(found?.updatedAt).isGreaterThan(record.updatedAt)
    }
    
    @Test
    @DisplayName("존재하지 않는 레코드 업데이트 시 실패")
    fun updateNonExistentRecord() = runBlocking {
        val record = createTestRecord()
        val result = storage.update(record)
        
        assertThat(result).isInstanceOf(StorageResult.Failure::class.java)
        assertThat((result as StorageResult.Failure).error).contains("not found")
    }
    
    @Test
    @DisplayName("배치 저장")
    fun saveBatch() = runBlocking {
        val records = (1..5).map { createTestRecord() }
        
        val result = storage.saveBatch(records)
        
        assertThat(result.successful).hasSize(5)
        assertThat(result.failed).isEmpty()
        assertThat(result.isFullSuccess()).isTrue
        assertThat(result.successRate).isEqualTo(1.0)
        
        records.forEach { record ->
            val found = storage.findById(record.id)
            assertThat(found).isNotNull
        }
    }
    
    @Test
    @DisplayName("배치 조회")
    fun findBatch() = runBlocking {
        val records = (1..5).map { createTestRecord() }
        records.forEach { storage.save(it) }
        
        val ids = records.map { it.id } + listOf("non-existent")
        val found = storage.findBatch(ids)
        
        assertThat(found).hasSize(5)
        assertThat(found.map { it.id }).containsExactlyInAnyOrderElementsOf(records.map { it.id })
    }
    
    @Test
    @DisplayName("배치 삭제")
    fun deleteBatch() = runBlocking {
        val records = (1..5).map { createTestRecord() }
        records.forEach { storage.save(it) }
        
        val ids = records.take(3).map { it.id } + listOf("non-existent")
        val deletedCount = storage.deleteBatch(ids)
        
        assertThat(deletedCount).isEqualTo(3)
        assertThat(storage.totalCount()).isEqualTo(2)
    }
    
    @Test
    @DisplayName("상태별 레코드 조회")
    fun findByStatus() = runBlocking {
        storage.save(createTestRecord(status = DLQStatus.PENDING))
        storage.save(createTestRecord(status = DLQStatus.PENDING))
        storage.save(createTestRecord(status = DLQStatus.FAILED))
        storage.save(createTestRecord(status = DLQStatus.SUCCESS))
        
        val criteria = QueryCriteria(statuses = setOf(DLQStatus.PENDING))
        val found = storage.find(criteria)
        
        assertThat(found).hasSize(2)
        assertThat(found).allMatch { it.status == DLQStatus.PENDING }
    }
    
    @Test
    @DisplayName("에러 타입별 레코드 조회")
    fun findByErrorType() = runBlocking {
        storage.save(createTestRecord(errorType = ErrorType.TRANSIENT))
        storage.save(createTestRecord(errorType = ErrorType.TRANSIENT))
        storage.save(createTestRecord(errorType = ErrorType.PERMANENT))
        
        val criteria = QueryCriteria(errorTypes = setOf(ErrorType.TRANSIENT))
        val found = storage.find(criteria)
        
        assertThat(found).hasSize(2)
        assertThat(found).allMatch { it.errorType == ErrorType.TRANSIENT }
    }
    
    @Test
    @DisplayName("날짜 범위로 레코드 조회")
    fun findByDateRange() = runBlocking {
        val now = System.currentTimeMillis()
        val hourAgo = now - 3600_000
        val twoHoursAgo = now - 7200_000
        
        storage.save(createTestRecord(createdAt = twoHoursAgo))
        storage.save(createTestRecord(createdAt = hourAgo))
        storage.save(createTestRecord(createdAt = now))
        
        val criteria = QueryCriteria(
            createdAfter = Instant.ofEpochMilli(hourAgo - 1000),
            createdBefore = Instant.ofEpochMilli(now + 1000)
        )
        val found = storage.find(criteria)
        
        assertThat(found).hasSize(2)
    }
    
    @Test
    @DisplayName("재시도 횟수로 레코드 조회")
    fun findByRetryCount() = runBlocking {
        storage.save(createTestRecord(retryCount = 0))
        storage.save(createTestRecord(retryCount = 2))
        storage.save(createTestRecord(retryCount = 5))
        
        val criteria = QueryCriteria(retryCountLessThan = 3)
        val found = storage.find(criteria)
        
        assertThat(found).hasSize(2)
        assertThat(found).allMatch { it.retryCount < 3 }
    }
    
    @Test
    @DisplayName("정렬 및 페이징")
    fun sortingAndPaging() = runBlocking {
        val records = (1..10).map { 
            createTestRecord(retryCount = it, createdAt = System.currentTimeMillis() + it * 1000) 
        }
        records.forEach { storage.save(it) }
        
        val criteria = QueryCriteria(
            limit = 3,
            offset = 2,
            sortBy = SortField.RETRY_COUNT,
            sortOrder = SortOrder.DESC
        )
        val found = storage.find(criteria)
        
        assertThat(found).hasSize(3)
        assertThat(found[0].retryCount).isEqualTo(8)
        assertThat(found[1].retryCount).isEqualTo(7)
        assertThat(found[2].retryCount).isEqualTo(6)
    }
    
    @Test
    @DisplayName("스트리밍 조회")
    fun streamRecords() = runBlocking {
        val records = (1..5).map { createTestRecord() }
        records.forEach { storage.save(it) }
        
        val criteria = QueryCriteria(limit = 3)
        val streamed = storage.stream(criteria).toList()
        
        assertThat(streamed).hasSize(3)
    }
    
    @Test
    @DisplayName("일괄 업데이트")
    fun bulkUpdate() = runBlocking {
        val records = (1..5).map { createTestRecord(status = DLQStatus.PENDING) }
        records.forEach { storage.save(it) }
        
        val criteria = QueryCriteria(statuses = setOf(DLQStatus.PENDING))
        val updateCount = storage.bulkUpdate(criteria) { record ->
            record.copy(status = DLQStatus.RETRYING, retryCount = record.retryCount + 1)
        }
        
        assertThat(updateCount).isEqualTo(5)
        
        val updated = storage.find(QueryCriteria())
        assertThat(updated).allMatch { it.status == DLQStatus.RETRYING }
        assertThat(updated).allMatch { it.retryCount == 1 }
    }
    
    @Test
    @DisplayName("일괄 삭제")
    fun bulkDelete() = runBlocking {
        storage.save(createTestRecord(status = DLQStatus.SUCCESS))
        storage.save(createTestRecord(status = DLQStatus.SUCCESS))
        storage.save(createTestRecord(status = DLQStatus.FAILED))
        
        val criteria = QueryCriteria(statuses = setOf(DLQStatus.SUCCESS))
        val deletedCount = storage.bulkDelete(criteria)
        
        assertThat(deletedCount).isEqualTo(2)
        assertThat(storage.totalCount()).isEqualTo(1)
    }
    
    @Test
    @DisplayName("전체 레코드 수 조회")
    fun totalCount() = runBlocking {
        assertThat(storage.totalCount()).isEqualTo(0)
        
        repeat(5) { storage.save(createTestRecord()) }
        assertThat(storage.totalCount()).isEqualTo(5)
        
        storage.deleteById(storage.find(QueryCriteria()).first().id)
        assertThat(storage.totalCount()).isEqualTo(4)
    }
    
    @Test
    @DisplayName("전체 레코드 삭제")
    fun clearAll() = runBlocking {
        repeat(10) { storage.save(createTestRecord()) }
        assertThat(storage.totalCount()).isEqualTo(10)
        
        storage.clear()
        assertThat(storage.totalCount()).isEqualTo(0)
    }
    
    @Test
    @DisplayName("최대 크기 제한 - REJECT 정책")
    fun maxSizeWithRejectPolicy() = runBlocking {
        val smallStorage = InMemoryStorage(maxSize = 3, evictionPolicy = EvictionPolicy.REJECT)
        
        repeat(3) { smallStorage.save(createTestRecord()) }
        assertThat(smallStorage.totalCount()).isEqualTo(3)
        
        val result = smallStorage.save(createTestRecord())
        assertThat(result).isInstanceOf(StorageResult.Failure::class.java)
        assertThat((result as StorageResult.Failure).error).contains("capacity exceeded")
        assertThat(smallStorage.totalCount()).isEqualTo(3)
    }
    
    @Test
    @DisplayName("최대 크기 제한 - REMOVE_OLDEST 정책")
    fun maxSizeWithRemoveOldestPolicy() = runBlocking {
        val smallStorage = InMemoryStorage(maxSize = 3, evictionPolicy = EvictionPolicy.REMOVE_OLDEST)
        
        val records = (1..3).map { createTestRecord() }
        records.forEach { smallStorage.save(it) }
        
        val newRecord = createTestRecord()
        val result = smallStorage.save(newRecord)
        
        assertThat(result).isInstanceOf(StorageResult.Success::class.java)
        assertThat(smallStorage.totalCount()).isEqualTo(3)
        assertThat(smallStorage.findById(records[0].id)).isNull() // 가장 오래된 레코드 제거됨
        assertThat(smallStorage.findById(newRecord.id)).isNotNull
    }
    
    @Test
    @DisplayName("스토리지 통계 확인")
    fun storageStats() = runBlocking {
        storage.save(createTestRecord(status = DLQStatus.PENDING, errorType = ErrorType.TRANSIENT))
        storage.save(createTestRecord(status = DLQStatus.PENDING, errorType = ErrorType.PERMANENT))
        storage.save(createTestRecord(status = DLQStatus.FAILED, errorType = ErrorType.TRANSIENT))
        
        val stats = storage.getStats()
        
        assertThat(stats.totalRecords).isEqualTo(3)
        assertThat(stats.byStatus[DLQStatus.PENDING]).isEqualTo(2)
        assertThat(stats.byStatus[DLQStatus.FAILED]).isEqualTo(1)
        assertThat(stats.byErrorType[ErrorType.TRANSIENT]).isEqualTo(2)
        assertThat(stats.byErrorType[ErrorType.PERMANENT]).isEqualTo(1)
        assertThat(stats.memoryUsageBytes).isGreaterThan(0)
    }
    
    private fun createTestRecord(
        id: String = UUID.randomUUID().toString(),
        status: DLQStatus = DLQStatus.PENDING,
        errorType: ErrorType = ErrorType.TRANSIENT,
        retryCount: Int = 0,
        createdAt: Long = System.currentTimeMillis()
    ): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = "key-$id",
            originalTopic = "test-topic",
            originalPartition = 0,
            originalOffset = 100L,
            payload = "test payload".toByteArray(),
            headers = mapOf("header1" to "value1".toByteArray()),
            errorClass = "TestException",
            errorMessage = "Test error",
            errorType = errorType,
            stackTrace = "stack trace",
            status = status,
            retryCount = retryCount,
            createdAt = createdAt,
            updatedAt = createdAt
        )
    }
}