package io.github.kayden.dlq.core.storage

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 메모리 기반 DLQ 스토리지 구현.
 * 
 * 테스트와 개발 환경에서 사용하기 위한 간단한 구현이다.
 * 프로덕션 환경에서는 영구 저장소를 사용해야 한다.
 * 
 * 특징:
 * - Thread-safe 구현 (ConcurrentHashMap + Mutex)
 * - 버전 관리를 통한 낙관적 동시성 제어
 * - 고성능 인메모리 연산
 * - 정규식 기반 패턴 매칭 지원
 * 
 * @property maxSize 최대 저장 가능한 레코드 수 (기본값: 100,000)
 * @property enableVersioning 버전 관리 활성화 여부 (기본값: true)
 * 
 * @since 0.1.0
 */
class InMemoryStorage(
    private val maxSize: Int = 100_000,
    private val enableVersioning: Boolean = true
) : DLQStorage {
    
    private val storage = ConcurrentHashMap<String, VersionedRecord>()
    private val mutex = Mutex()
    private val versionCounter = AtomicLong(0)
    
    /**
     * 버전 정보가 포함된 레코드.
     */
    private data class VersionedRecord(
        val record: DLQRecord,
        val version: Long = 0
    )
    
    override suspend fun save(record: DLQRecord): StorageResult = mutex.withLock {
        if (storage.size >= maxSize) {
            return StorageResult.Failure(
                recordId = record.id,
                errorType = StorageErrorType.CAPACITY_EXCEEDED,
                message = "Storage capacity exceeded: $maxSize"
            )
        }
        
        val version = if (enableVersioning) versionCounter.incrementAndGet() else 0
        storage[record.id] = VersionedRecord(record, version)
        
        return StorageResult.Success(
            recordId = record.id,
            record = record,
            message = "Record saved successfully"
        )
    }
    
    override suspend fun saveAll(records: List<DLQRecord>): List<StorageResult> = mutex.withLock {
        if (storage.size + records.size > maxSize) {
            return records.map { record ->
                StorageResult.Failure(
                    recordId = record.id,
                    errorType = StorageErrorType.CAPACITY_EXCEEDED,
                    message = "Storage capacity would be exceeded"
                )
            }
        }
        
        return records.map { record ->
            val version = if (enableVersioning) versionCounter.incrementAndGet() else 0
            storage[record.id] = VersionedRecord(record, version)
            StorageResult.Success(
                recordId = record.id,
                record = record,
                message = "Record saved successfully"
            )
        }
    }
    
    override suspend fun findById(id: String): DLQRecord? {
        return storage[id]?.record
    }
    
    override fun findByCriteria(criteria: QueryCriteria): Flow<DLQRecord> {
        return storage.values
            .asSequence()
            .map { it.record }
            .filter { record -> matchesCriteria(record, criteria) }
            .sortedWith(getComparator(criteria.sortBy, criteria.sortOrder))
            .drop(criteria.offset)
            .take(criteria.limit)
            .asFlow()
    }
    
    override fun findByStatus(status: DLQStatus, limit: Int): Flow<DLQRecord> {
        return storage.values
            .asSequence()
            .map { it.record }
            .filter { it.status == status }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .asFlow()
    }
    
    override fun findRetryableRecords(limit: Int): Flow<DLQRecord> {
        val retryableStatuses = setOf(DLQStatus.PENDING, DLQStatus.RETRYING)
        return storage.values
            .asSequence()
            .map { it.record }
            .filter { it.status in retryableStatuses }
            .sortedBy { it.retryCount }
            .take(limit)
            .asFlow()
    }
    
    override suspend fun update(record: DLQRecord): StorageResult = mutex.withLock {
        val existing = storage[record.id]
        if (existing == null) {
            return StorageResult.NotFound(record.id)
        }
        
        if (enableVersioning) {
            val newVersion = versionCounter.incrementAndGet()
            storage[record.id] = VersionedRecord(record, newVersion)
        } else {
            storage[record.id] = VersionedRecord(record, 0)
        }
        
        return StorageResult.Success(
            recordId = record.id,
            record = record,
            message = "Record updated successfully"
        )
    }
    
    override suspend fun updateAll(records: List<DLQRecord>): List<StorageResult> = mutex.withLock {
        return records.map { record ->
            val existing = storage[record.id]
            if (existing == null) {
                StorageResult.NotFound(record.id)
            } else {
                val newVersion = if (enableVersioning) versionCounter.incrementAndGet() else 0
                storage[record.id] = VersionedRecord(record, newVersion)
                StorageResult.Success(
                    recordId = record.id,
                    record = record,
                    message = "Record updated successfully"
                )
            }
        }
    }
    
    override suspend fun delete(id: String): StorageResult = mutex.withLock {
        val removed = storage.remove(id)
        return if (removed != null) {
            StorageResult.Success(
                recordId = id,
                message = "Record deleted successfully"
            )
        } else {
            StorageResult.NotFound(id)
        }
    }
    
    override suspend fun deleteByCriteria(criteria: QueryCriteria): Int = mutex.withLock {
        val toDelete = storage.values
            .filter { matchesCriteria(it.record, criteria) }
            .map { it.record.id }
            .toList()
        
        toDelete.forEach { storage.remove(it) }
        return toDelete.size
    }
    
    override suspend fun deleteOldRecords(
        beforeTimestamp: Long,
        statuses: Set<DLQStatus>
    ): Int = mutex.withLock {
        val toDelete = storage.values
            .filter { versioned ->
                val record = versioned.record
                record.createdAt < beforeTimestamp && record.status in statuses
            }
            .map { it.record.id }
            .toList()
        
        toDelete.forEach { storage.remove(it) }
        return toDelete.size
    }
    
    override suspend fun count(status: DLQStatus?): Long {
        return if (status == null) {
            storage.size.toLong()
        } else {
            storage.values.count { it.record.status == status }.toLong()
        }
    }
    
    override suspend fun getStatistics(): StorageStatistics {
        val records = storage.values.map { it.record }
        
        val recordsByStatus = DLQStatus.values().associateWith { status ->
            records.count { it.status == status }.toLong()
        }
        
        val timestamps = records.map { it.createdAt }.sorted()
        val oldestTimestamp = timestamps.firstOrNull()
        val newestTimestamp = timestamps.lastOrNull()
        
        val averageSize = if (records.isNotEmpty()) {
            records.map { it.payload.size.toLong() }.average().toLong()
        } else {
            0L
        }
        
        val totalSize = records.sumOf { it.payload.size.toLong() }
        
        return StorageStatistics(
            totalRecords = records.size.toLong(),
            recordsByStatus = recordsByStatus,
            oldestRecordTimestamp = oldestTimestamp,
            newestRecordTimestamp = newestTimestamp,
            averageRecordSize = averageSize,
            totalStorageSize = totalSize
        )
    }
    
    override suspend fun isHealthy(): Boolean {
        return storage.size < maxSize * 0.9 // 90% 미만이면 정상
    }
    
    override suspend fun clear() = mutex.withLock {
        storage.clear()
        versionCounter.set(0)
    }
    
    /**
     * 레코드가 조회 조건에 맞는지 확인한다.
     */
    private fun matchesCriteria(record: DLQRecord, criteria: QueryCriteria): Boolean {
        // 상태 필터
        criteria.statuses?.let { statuses ->
            if (record.status !in statuses) return false
        }
        
        // 에러 타입 필터
        criteria.errorTypes?.let { errorTypes ->
            if (record.errorType !in errorTypes) return false
        }
        
        // 토픽 필터
        criteria.topics?.let { topics ->
            if (record.originalTopic !in topics) return false
        }
        
        // 메시지 키 패턴
        criteria.messageKeyPattern?.let { pattern ->
            record.messageKey?.let { key ->
                if (!key.matches(Regex(pattern))) return false
            } ?: return false
        }
        
        // 에러 메시지 패턴
        criteria.errorMessagePattern?.let { pattern ->
            record.errorMessage?.let { msg ->
                if (!msg.matches(Regex(pattern))) return false
            } ?: return false
        }
        
        // 생성 시간 필터
        criteria.createdAfter?.let { after ->
            if (record.createdAt <= after) return false
        }
        criteria.createdBefore?.let { before ->
            if (record.createdAt >= before) return false
        }
        
        // 마지막 처리 시간 필터
        criteria.lastProcessedAfter?.let { after ->
            if ((record.updatedAt ?: 0) <= after) return false
        }
        criteria.lastProcessedBefore?.let { before ->
            if ((record.updatedAt ?: Long.MAX_VALUE) >= before) return false
        }
        
        // 재시도 횟수 필터
        criteria.retryCountMin?.let { min ->
            if (record.retryCount < min) return false
        }
        criteria.retryCountMax?.let { max ->
            if (record.retryCount > max) return false
        }
        
        // 페이로드 크기 필터
        criteria.payloadSizeMin?.let { min ->
            if (record.payload.size < min) return false
        }
        criteria.payloadSizeMax?.let { max ->
            if (record.payload.size > max) return false
        }
        
        return true
    }
    
    /**
     * 정렬 조건에 따른 Comparator를 생성한다.
     */
    private fun getComparator(
        sortBy: SortField,
        sortOrder: SortOrder
    ): Comparator<DLQRecord> {
        val comparator = when (sortBy) {
            SortField.CREATED_AT -> compareBy<DLQRecord> { it.createdAt }
            SortField.LAST_PROCESSED_AT -> compareBy { it.updatedAt ?: 0 }
            SortField.RETRY_COUNT -> compareBy { it.retryCount }
            SortField.PAYLOAD_SIZE -> compareBy { it.payload.size }
            SortField.MESSAGE_KEY -> compareBy { it.messageKey }
            SortField.ERROR_TYPE -> compareBy { it.errorType.name }
        }
        
        return if (sortOrder == SortOrder.DESC) {
            comparator.reversed()
        } else {
            comparator
        }
    }
    
    /**
     * 현재 저장된 레코드 수를 반환한다.
     * 테스트 용도로 사용한다.
     */
    fun size(): Int = storage.size
    
    /**
     * 특정 레코드의 버전을 반환한다.
     * 테스트 용도로 사용한다.
     */
    fun getVersion(id: String): Long? = storage[id]?.version
}