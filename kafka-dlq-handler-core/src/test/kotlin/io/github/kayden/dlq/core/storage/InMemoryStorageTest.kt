package io.github.kayden.dlq.core.storage

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days

class InMemoryStorageTest {
    
    private lateinit var storage: InMemoryStorage
    
    @BeforeEach
    fun setUp() {
        storage = InMemoryStorage()
    }
    
    @Test
    @DisplayName("레코드를 저장할 수 있다")
    fun shouldSaveRecord() = runTest {
        val record = createTestRecord("test-1")
        val result = storage.save(record)
        
        assertTrue(result is StorageResult.Success)
        assertEquals("test-1", (result as StorageResult.Success).recordId)
        assertEquals(record, result.record)
        
        val found = storage.findById("test-1")
        assertNotNull(found)
        assertEquals(record, found)
    }
    
    @Test
    @DisplayName("여러 레코드를 배치로 저장할 수 있다")
    fun shouldSaveAllRecords() = runTest {
        val records = (1..5).map { createTestRecord("test-$it") }
        val results = storage.saveAll(records)
        
        assertEquals(5, results.size)
        assertTrue(results.all { it is StorageResult.Success })
        
        records.forEach { record ->
            val found = storage.findById(record.id)
            assertNotNull(found)
            assertEquals(record, found)
        }
    }
    
    @Test
    @DisplayName("용량 초과 시 저장이 실패한다")
    fun shouldFailWhenCapacityExceeded() = runTest {
        val smallStorage = InMemoryStorage(maxSize = 2)
        
        val result1 = smallStorage.save(createTestRecord("test-1"))
        assertTrue(result1 is StorageResult.Success)
        
        val result2 = smallStorage.save(createTestRecord("test-2"))
        assertTrue(result2 is StorageResult.Success)
        
        val result3 = smallStorage.save(createTestRecord("test-3"))
        assertTrue(result3 is StorageResult.Failure)
        assertEquals(StorageErrorType.CAPACITY_EXCEEDED, (result3 as StorageResult.Failure).errorType)
    }
    
    @Test
    @DisplayName("상태별로 레코드를 조회할 수 있다")
    fun shouldFindByStatus() = runTest {
        storage.save(createTestRecord("test-1", status = DLQStatus.PENDING))
        storage.save(createTestRecord("test-2", status = DLQStatus.PENDING))
        storage.save(createTestRecord("test-3", status = DLQStatus.SUCCESS))
        storage.save(createTestRecord("test-4", status = DLQStatus.FAILED))
        
        val pending = storage.findByStatus(DLQStatus.PENDING).toList()
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.status == DLQStatus.PENDING })
        
        val success = storage.findByStatus(DLQStatus.SUCCESS).toList()
        assertEquals(1, success.size)
        assertEquals("test-3", success.first().id)
    }
    
    @Test
    @DisplayName("재처리 대상 레코드를 조회할 수 있다")
    fun shouldFindRetryableRecords() = runTest {
        storage.save(createTestRecord("test-1", status = DLQStatus.PENDING, retryCount = 0))
        storage.save(createTestRecord("test-2", status = DLQStatus.RETRYING, retryCount = 2))
        storage.save(createTestRecord("test-3", status = DLQStatus.SUCCESS, retryCount = 0))
        storage.save(createTestRecord("test-4", status = DLQStatus.PENDING, retryCount = 1))
        
        val retryable = storage.findRetryableRecords().toList()
        assertEquals(3, retryable.size)
        assertTrue(retryable.all { it.status in setOf(DLQStatus.PENDING, DLQStatus.RETRYING) })
        
        // 재시도 횟수 오름차순 정렬 확인
        assertEquals(0, retryable[0].retryCount)
        assertEquals(1, retryable[1].retryCount)
        assertEquals(2, retryable[2].retryCount)
    }
    
    @Test
    @DisplayName("복잡한 조건으로 레코드를 조회할 수 있다")
    fun shouldFindByCriteria() = runTest {
        val currentTime = System.currentTimeMillis()
        
        // 다양한 레코드 생성
        storage.save(createTestRecord("test-1", 
            status = DLQStatus.PENDING,
            errorType = ErrorType.TRANSIENT_NETWORK_ERROR,
            topic = "topic-1",
            retryCount = 2,
            createdAt = currentTime - 1.hours.inWholeMilliseconds
        ))
        storage.save(createTestRecord("test-2",
            status = DLQStatus.PENDING,
            errorType = ErrorType.TRANSIENT_SERVICE_ERROR,
            topic = "topic-2",
            retryCount = 5,
            createdAt = currentTime - 2.hours.inWholeMilliseconds
        ))
        storage.save(createTestRecord("test-3",
            status = DLQStatus.FAILED,
            errorType = ErrorType.TRANSIENT_NETWORK_ERROR,
            topic = "topic-1",
            retryCount = 10,
            createdAt = currentTime - 3.hours.inWholeMilliseconds
        ))
        
        // 복합 조건 조회
        val criteria = QueryCriteria(
            statuses = setOf(DLQStatus.PENDING),
            errorTypes = setOf(ErrorType.TRANSIENT_NETWORK_ERROR),
            topics = setOf("topic-1"),
            retryCountMin = 1,
            retryCountMax = 5,
            createdAfter = currentTime - 2.hours.inWholeMilliseconds
        )
        
        val results = storage.findByCriteria(criteria).toList()
        assertEquals(1, results.size)
        assertEquals("test-1", results.first().id)
    }
    
    @Test
    @DisplayName("메시지 키 패턴으로 조회할 수 있다")
    fun shouldFindByMessageKeyPattern() = runTest {
        storage.save(createTestRecord("test-1", messageKey = "user-123"))
        storage.save(createTestRecord("test-2", messageKey = "user-456"))
        storage.save(createTestRecord("test-3", messageKey = "order-789"))
        
        val criteria = QueryCriteria(messageKeyPattern = "user-.*")
        val results = storage.findByCriteria(criteria).toList()
        
        assertEquals(2, results.size)
        assertTrue(results.all { it.messageKey?.startsWith("user-") == true })
    }
    
    @Test
    @DisplayName("레코드를 업데이트할 수 있다")
    fun shouldUpdateRecord() = runTest {
        val original = createTestRecord("test-1", status = DLQStatus.PENDING)
        storage.save(original)
        
        val updated = original.copy(
            status = DLQStatus.SUCCESS,
            retryCount = 3,
            updatedAt = System.currentTimeMillis()
        )
        
        val result = storage.update(updated)
        assertTrue(result is StorageResult.Success)
        
        val found = storage.findById("test-1")
        assertNotNull(found)
        assertEquals(DLQStatus.SUCCESS, found!!.status)
        assertEquals(3, found.retryCount)
        assertNotNull(found.updatedAt)
    }
    
    @Test
    @DisplayName("존재하지 않는 레코드 업데이트 시 NotFound를 반환한다")
    fun shouldReturnNotFoundForNonExistentUpdate() = runTest {
        val record = createTestRecord("non-existent")
        val result = storage.update(record)
        
        assertTrue(result is StorageResult.NotFound)
        assertEquals("non-existent", (result as StorageResult.NotFound).recordId)
    }
    
    @Test
    @DisplayName("레코드를 삭제할 수 있다")
    fun shouldDeleteRecord() = runTest {
        val record = createTestRecord("test-1")
        storage.save(record)
        
        val result = storage.delete("test-1")
        assertTrue(result is StorageResult.Success)
        
        val found = storage.findById("test-1")
        assertNull(found)
    }
    
    @Test
    @DisplayName("조건에 맞는 레코드를 삭제할 수 있다")
    fun shouldDeleteByCriteria() = runTest {
        storage.save(createTestRecord("test-1", status = DLQStatus.SUCCESS))
        storage.save(createTestRecord("test-2", status = DLQStatus.SUCCESS))
        storage.save(createTestRecord("test-3", status = DLQStatus.FAILED))
        
        val criteria = QueryCriteria(statuses = setOf(DLQStatus.SUCCESS))
        val deleted = storage.deleteByCriteria(criteria)
        
        assertEquals(2, deleted)
        assertEquals(1, storage.count())
        assertNotNull(storage.findById("test-3"))
    }
    
    @Test
    @DisplayName("오래된 레코드를 삭제할 수 있다")
    fun shouldDeleteOldRecords() = runTest {
        val currentTime = System.currentTimeMillis()
        
        storage.save(createTestRecord("test-1", 
            status = DLQStatus.SUCCESS,
            createdAt = currentTime - 10.days.inWholeMilliseconds
        ))
        storage.save(createTestRecord("test-2",
            status = DLQStatus.FAILED,
            createdAt = currentTime - 5.days.inWholeMilliseconds
        ))
        storage.save(createTestRecord("test-3",
            status = DLQStatus.PENDING,
            createdAt = currentTime - 10.days.inWholeMilliseconds
        ))
        storage.save(createTestRecord("test-4",
            status = DLQStatus.SUCCESS,
            createdAt = currentTime - 1.days.inWholeMilliseconds
        ))
        
        val deleted = storage.deleteOldRecords(
            beforeTimestamp = currentTime - 7.days.inWholeMilliseconds
        )
        
        assertEquals(2, deleted) // test-1과 test-2만 삭제
        assertEquals(2, storage.count())
    }
    
    @Test
    @DisplayName("저장소 통계를 조회할 수 있다")
    fun shouldGetStatistics() = runTest {
        val currentTime = System.currentTimeMillis()
        
        storage.save(createTestRecord("test-1",
            status = DLQStatus.PENDING,
            createdAt = currentTime - 2.hours.inWholeMilliseconds,
            payload = ByteArray(1000)
        ))
        storage.save(createTestRecord("test-2",
            status = DLQStatus.SUCCESS,
            createdAt = currentTime - 1.hours.inWholeMilliseconds,
            payload = ByteArray(2000)
        ))
        storage.save(createTestRecord("test-3",
            status = DLQStatus.FAILED,
            createdAt = currentTime,
            payload = ByteArray(3000)
        ))
        
        val stats = storage.getStatistics()
        
        assertEquals(3, stats.totalRecords)
        assertEquals(1L, stats.recordsByStatus[DLQStatus.PENDING])
        assertEquals(1L, stats.recordsByStatus[DLQStatus.SUCCESS])
        assertEquals(1L, stats.recordsByStatus[DLQStatus.FAILED])
        assertEquals(0L, stats.recordsByStatus[DLQStatus.RETRYING])
        
        assertEquals(currentTime - 2.hours.inWholeMilliseconds, stats.oldestRecordTimestamp)
        assertEquals(currentTime, stats.newestRecordTimestamp)
        assertEquals(2000L, stats.averageRecordSize) // (1000 + 2000 + 3000) / 3
        assertEquals(6000L, stats.totalStorageSize)
    }
    
    @Test
    @DisplayName("저장소 상태를 확인할 수 있다")
    fun shouldCheckHealth() = runTest {
        val smallStorage = InMemoryStorage(maxSize = 10)
        
        assertTrue(smallStorage.isHealthy())
        
        // 80% 채우기
        repeat(8) { i ->
            smallStorage.save(createTestRecord("test-$i"))
        }
        assertTrue(smallStorage.isHealthy())
        
        // 90% 채우기
        smallStorage.save(createTestRecord("test-9"))
        assertFalse(smallStorage.isHealthy())
    }
    
    @Test
    @DisplayName("저장소를 초기화할 수 있다")
    fun shouldClearStorage() = runTest {
        repeat(5) { i ->
            storage.save(createTestRecord("test-$i"))
        }
        
        assertEquals(5, storage.count())
        
        storage.clear()
        
        assertEquals(0, storage.count())
        assertNull(storage.findById("test-1"))
    }
    
    @Test
    @DisplayName("정렬이 올바르게 동작한다")
    fun shouldSortCorrectly() = runTest {
        val currentTime = System.currentTimeMillis()
        
        storage.save(createTestRecord("test-1",
            createdAt = currentTime - 3.hours.inWholeMilliseconds,
            retryCount = 5,
            payload = ByteArray(3000)
        ))
        storage.save(createTestRecord("test-2",
            createdAt = currentTime - 1.hours.inWholeMilliseconds,
            retryCount = 2,
            payload = ByteArray(1000)
        ))
        storage.save(createTestRecord("test-3",
            createdAt = currentTime - 2.hours.inWholeMilliseconds,
            retryCount = 8,
            payload = ByteArray(2000)
        ))
        
        // 생성 시간 내림차순 (기본값)
        val byCreatedDesc = storage.findByCriteria(QueryCriteria()).toList()
        assertEquals("test-2", byCreatedDesc[0].id)
        assertEquals("test-3", byCreatedDesc[1].id)
        assertEquals("test-1", byCreatedDesc[2].id)
        
        // 재시도 횟수 오름차순
        val byRetryAsc = storage.findByCriteria(
            QueryCriteria(sortBy = SortField.RETRY_COUNT, sortOrder = SortOrder.ASC)
        ).toList()
        assertEquals("test-2", byRetryAsc[0].id)
        assertEquals("test-1", byRetryAsc[1].id)
        assertEquals("test-3", byRetryAsc[2].id)
        
        // 페이로드 크기 내림차순
        val byPayloadDesc = storage.findByCriteria(
            QueryCriteria(sortBy = SortField.PAYLOAD_SIZE, sortOrder = SortOrder.DESC)
        ).toList()
        assertEquals("test-1", byPayloadDesc[0].id)
        assertEquals("test-3", byPayloadDesc[1].id)
        assertEquals("test-2", byPayloadDesc[2].id)
    }
    
    @Test
    @DisplayName("페이지네이션이 올바르게 동작한다")
    fun shouldPaginateCorrectly() = runTest {
        repeat(10) { i ->
            storage.save(createTestRecord("test-$i"))
        }
        
        // 첫 페이지
        val page1 = storage.findByCriteria(
            QueryCriteria(offset = 0, limit = 3)
        ).toList()
        assertEquals(3, page1.size)
        
        // 두 번째 페이지
        val page2 = storage.findByCriteria(
            QueryCriteria(offset = 3, limit = 3)
        ).toList()
        assertEquals(3, page2.size)
        
        // 마지막 페이지
        val lastPage = storage.findByCriteria(
            QueryCriteria(offset = 9, limit = 3)
        ).toList()
        assertEquals(1, lastPage.size)
        
        // 중복 없음 확인
        val allIds = (page1 + page2 + lastPage).map { it.id }.toSet()
        assertEquals(7, allIds.size)
    }
    
    @Test
    @DisplayName("버전 관리가 활성화된 경우 버전이 증가한다")
    fun shouldIncrementVersionOnUpdate() = runTest {
        val versionedStorage = InMemoryStorage(enableVersioning = true)
        
        val record = createTestRecord("test-1")
        versionedStorage.save(record)
        
        val version1 = versionedStorage.getVersion("test-1")
        assertNotNull(version1)
        
        val updated = record.copy(status = DLQStatus.SUCCESS)
        versionedStorage.update(updated)
        
        val version2 = versionedStorage.getVersion("test-1")
        assertNotNull(version2)
        assertTrue(version2!! > version1!!)
    }
    
    private fun createTestRecord(
        id: String,
        status: DLQStatus = DLQStatus.PENDING,
        errorType: ErrorType = ErrorType.TRANSIENT_NETWORK_ERROR,
        topic: String = "test-topic",
        messageKey: String = "test-key",
        retryCount: Int = 0,
        createdAt: Long = System.currentTimeMillis(),
        payload: ByteArray = "test payload".toByteArray()
    ): DLQRecord {
        return DLQRecord(
            id = id,
            messageKey = messageKey,
            originalTopic = topic,
            originalPartition = 0,
            originalOffset = 123L,
            payload = payload,
            headers = emptyMap(),
            errorClass = "TestException",
            errorMessage = "Test error",
            errorType = errorType,
            stackTrace = null,
            status = status,
            retryCount = retryCount,
            createdAt = createdAt
        )
    }
}