package io.github.kayden.dlq.storage

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 고성능 메모리 기반 DLQ 스토리지 구현
 * 
 * ConcurrentHashMap을 사용하여 thread-safe를 보장하며,
 * 대규모 트래픽 처리를 위한 최적화가 적용되었습니다.
 * 
 * 특징:
 * - Thread-safe 작업 보장
 * - O(1) 단일 레코드 접근
 * - 효율적인 배치 처리
 * - 메모리 사용량 모니터링
 * 
 * @property maxSize 최대 저장 가능한 레코드 수 (기본값: 1,000,000)
 * @property evictionPolicy 최대 크기 도달 시 제거 정책
 */
class InMemoryStorage(
    private val maxSize: Int = 1_000_000,
    private val evictionPolicy: EvictionPolicy = EvictionPolicy.REJECT
) : DLQStorage {
    
    private val storage = ConcurrentHashMap<String, DLQRecord>(
        /* initialCapacity */ 16,
        /* loadFactor */ 0.75f,
        /* concurrencyLevel */ 16
    )
    
    private val totalCount = AtomicLong(0)
    private val mutex = Mutex()
    
    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }
    
    override suspend fun save(record: DLQRecord): StorageResult = mutex.withLock {
        if (storage.size >= maxSize) {
            when (evictionPolicy) {
                EvictionPolicy.REJECT -> {
                    return StorageResult.Failure(
                        "Storage capacity exceeded: ${storage.size}/$maxSize"
                    )
                }
                EvictionPolicy.REMOVE_OLDEST -> {
                    removeOldest()
                }
                EvictionPolicy.REMOVE_EXPIRED -> {
                    removeExpired()
                    if (storage.size >= maxSize) {
                        return StorageResult.Failure(
                            "Storage capacity exceeded after removing expired records"
                        )
                    }
                }
            }
        }
        
        storage[record.id] = record
        totalCount.incrementAndGet()
        StorageResult.Success(record.id)
    }
    
    override suspend fun findById(id: String): DLQRecord? = storage[id]
    
    override suspend fun deleteById(id: String): Boolean = storage.remove(id) != null
    
    override suspend fun update(record: DLQRecord): StorageResult {
        return if (storage.containsKey(record.id)) {
            storage[record.id] = record.copy(updatedAt = System.currentTimeMillis())
            StorageResult.Success(record.id)
        } else {
            StorageResult.Failure("Record not found: ${record.id}")
        }
    }
    
    override suspend fun saveBatch(records: List<DLQRecord>): BatchStorageResult {
        val startTime = Instant.now()
        val successful = mutableListOf<String>()
        val failed = mutableListOf<BatchStorageResult.FailedRecord>()
        
        records.forEachIndexed { index, record ->
            when (val result = save(record)) {
                is StorageResult.Success -> successful.add(result.id)
                is StorageResult.Failure -> failed.add(
                    BatchStorageResult.FailedRecord(index, result.error, result.cause)
                )
            }
        }
        
        return BatchStorageResult(
            successful = successful,
            failed = failed,
            duration = Duration.between(startTime, Instant.now())
        )
    }
    
    override suspend fun findBatch(ids: List<String>): List<DLQRecord> {
        return ids.mapNotNull { storage[it] }
    }
    
    override suspend fun deleteBatch(ids: List<String>): Int {
        return ids.count { storage.remove(it) != null }
    }
    
    override suspend fun find(criteria: QueryCriteria): List<DLQRecord> {
        return storage.values
            .asSequence()
            .filter { matches(it, criteria) }
            .sortedWith(getComparator(criteria))
            .drop(criteria.offset)
            .take(criteria.limit)
            .toList()
    }
    
    override suspend fun count(criteria: QueryCriteria): Long {
        return storage.values.count { matches(it, criteria) }.toLong()
    }
    
    override fun stream(criteria: QueryCriteria): Flow<DLQRecord> {
        return storage.values
            .asSequence()
            .filter { matches(it, criteria) }
            .sortedWith(getComparator(criteria))
            .drop(criteria.offset)
            .take(criteria.limit)
            .asFlow()
    }
    
    override suspend fun bulkUpdate(
        criteria: QueryCriteria,
        update: (DLQRecord) -> DLQRecord
    ): Int = mutex.withLock {
        var count = 0
        storage.entries.forEach { (id, record) ->
            if (matches(record, criteria)) {
                storage[id] = update(record).copy(updatedAt = System.currentTimeMillis())
                count++
            }
        }
        count
    }
    
    override suspend fun bulkDelete(criteria: QueryCriteria): Int = mutex.withLock {
        var count = 0
        storage.entries.removeIf { (_, record) ->
            if (matches(record, criteria)) {
                count++
                true
            } else false
        }
        count
    }
    
    override suspend fun totalCount(): Long = storage.size.toLong()
    
    override suspend fun clear() {
        storage.clear()
        totalCount.set(0)
    }
    
    override suspend fun optimize() {
        // 만료된 레코드 제거
        removeExpired()
    }
    
    /**
     * 레코드가 쿼리 조건에 맞는지 확인
     */
    private fun matches(record: DLQRecord, criteria: QueryCriteria): Boolean {
        // Status 필터
        if (criteria.statuses != null && record.status !in criteria.statuses) {
            return false
        }
        
        // ErrorType 필터
        if (criteria.errorTypes != null && record.errorType !in criteria.errorTypes) {
            return false
        }
        
        // Topic 필터
        if (criteria.originalTopic != null && record.originalTopic != criteria.originalTopic) {
            return false
        }
        
        // 생성 시간 필터
        val createdInstant = Instant.ofEpochMilli(record.createdAt)
        if (criteria.createdAfter != null && createdInstant.isBefore(criteria.createdAfter)) {
            return false
        }
        if (criteria.createdBefore != null && createdInstant.isAfter(criteria.createdBefore)) {
            return false
        }
        
        // 재시도 횟수 필터
        if (criteria.retryCountLessThan != null && record.retryCount >= criteria.retryCountLessThan) {
            return false
        }
        if (criteria.retryCountGreaterThan != null && record.retryCount <= criteria.retryCountGreaterThan) {
            return false
        }
        
        return true
    }
    
    /**
     * 정렬 기준에 따른 Comparator 생성
     */
    private fun getComparator(criteria: QueryCriteria): Comparator<DLQRecord> {
        val baseComparator = when (criteria.sortBy) {
            SortField.CREATED_AT -> compareBy<DLQRecord> { it.createdAt }
            SortField.UPDATED_AT -> compareBy { it.updatedAt }
            SortField.RETRY_COUNT -> compareBy { it.retryCount }
            SortField.ORIGINAL_OFFSET -> compareBy { it.originalOffset }
        }
        
        return if (criteria.sortOrder == SortOrder.DESC) {
            baseComparator.reversed()
        } else {
            baseComparator
        }
    }
    
    /**
     * 가장 오래된 레코드 제거
     */
    private fun removeOldest() {
        storage.entries
            .minByOrNull { it.value.createdAt }
            ?.let { storage.remove(it.key) }
    }
    
    /**
     * 만료된 레코드 제거
     * EXPIRED 상태이고 30일 이상 된 레코드를 제거
     */
    private fun removeExpired() {
        val thirtyDaysAgo = System.currentTimeMillis() - Duration.ofDays(30).toMillis()
        storage.entries.removeIf { (_, record) ->
            record.status == DLQStatus.EXPIRED && record.createdAt < thirtyDaysAgo
        }
    }
    
    /**
     * 스토리지 통계 정보
     */
    fun getStats(): StorageStats = StorageStats(
        totalRecords = storage.size.toLong(),
        totalInserted = totalCount.get(),
        byStatus = storage.values.groupingBy { it.status }.eachCount(),
        byErrorType = storage.values.groupingBy { it.errorType }.eachCount(),
        memoryUsageBytes = estimateMemoryUsage()
    )
    
    /**
     * 대략적인 메모리 사용량 추정
     */
    private fun estimateMemoryUsage(): Long {
        // 각 레코드당 약 1KB로 추정
        return storage.size * 1024L
    }
}

/**
 * 제거 정책
 */
enum class EvictionPolicy {
    /**
     * 새로운 레코드 저장을 거부
     */
    REJECT,
    
    /**
     * 가장 오래된 레코드를 제거
     */
    REMOVE_OLDEST,
    
    /**
     * 만료된 레코드를 제거
     */
    REMOVE_EXPIRED
}

/**
 * 스토리지 통계
 */
data class StorageStats(
    val totalRecords: Long,
    val totalInserted: Long,
    val byStatus: Map<DLQStatus, Int>,
    val byErrorType: Map<ErrorType, Int>,
    val memoryUsageBytes: Long
)