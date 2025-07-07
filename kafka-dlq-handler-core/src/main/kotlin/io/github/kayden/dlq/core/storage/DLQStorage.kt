package io.github.kayden.dlq.core.storage

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import kotlinx.coroutines.flow.Flow

/**
 * DLQ 메시지를 저장하고 조회하는 스토리지 인터페이스.
 * 
 * 프레임워크 중립적인 인터페이스로, 다양한 저장소 구현을 지원한다.
 * 모든 메서드는 코루틴을 사용하여 비동기적으로 동작한다.
 * 
 * 구현 예시:
 * - InMemoryStorage: 테스트 및 개발용
 * - JPA Storage: 관계형 데이터베이스 (별도 모듈)
 * - Redis Storage: 인메모리 캐시 (별도 모듈)
 * - Cassandra Storage: 시계열 데이터 (별도 모듈)
 * 
 * @since 0.1.0
 */
interface DLQStorage {
    
    /**
     * DLQ 레코드를 저장한다.
     * 
     * @param record 저장할 DLQ 레코드
     * @return 저장 결과
     */
    suspend fun save(record: DLQRecord): StorageResult
    
    /**
     * 여러 DLQ 레코드를 배치로 저장한다.
     * 고성능 처리를 위해 배치 저장을 지원한다.
     * 
     * @param records 저장할 DLQ 레코드 목록
     * @return 각 레코드의 저장 결과 목록
     */
    suspend fun saveAll(records: List<DLQRecord>): List<StorageResult>
    
    /**
     * ID로 DLQ 레코드를 조회한다.
     * 
     * @param id 레코드 ID
     * @return DLQ 레코드 또는 null
     */
    suspend fun findById(id: String): DLQRecord?
    
    /**
     * 특정 조건에 맞는 DLQ 레코드를 조회한다.
     * 
     * @param criteria 조회 조건
     * @return 조건에 맞는 DLQ 레코드 Flow
     */
    fun findByCriteria(criteria: QueryCriteria): Flow<DLQRecord>
    
    /**
     * 상태별로 DLQ 레코드를 조회한다.
     * 
     * @param status DLQ 상태
     * @param limit 최대 조회 개수 (기본값: 100)
     * @return 해당 상태의 DLQ 레코드 Flow
     */
    fun findByStatus(status: DLQStatus, limit: Int = 100): Flow<DLQRecord>
    
    /**
     * 재처리 대상 메시지를 조회한다.
     * PENDING 또는 RETRYING 상태이고 재시도 가능한 메시지를 반환한다.
     * 
     * @param limit 최대 조회 개수
     * @return 재처리 대상 DLQ 레코드 Flow
     */
    fun findRetryableRecords(limit: Int = 100): Flow<DLQRecord>
    
    /**
     * DLQ 레코드를 업데이트한다.
     * 
     * @param record 업데이트할 DLQ 레코드
     * @return 업데이트 결과
     */
    suspend fun update(record: DLQRecord): StorageResult
    
    /**
     * 여러 DLQ 레코드를 배치로 업데이트한다.
     * 
     * @param records 업데이트할 DLQ 레코드 목록
     * @return 각 레코드의 업데이트 결과 목록
     */
    suspend fun updateAll(records: List<DLQRecord>): List<StorageResult>
    
    /**
     * DLQ 레코드를 삭제한다.
     * 
     * @param id 삭제할 레코드 ID
     * @return 삭제 결과
     */
    suspend fun delete(id: String): StorageResult
    
    /**
     * 특정 조건에 맞는 DLQ 레코드를 모두 삭제한다.
     * 
     * @param criteria 삭제 조건
     * @return 삭제된 레코드 수
     */
    suspend fun deleteByCriteria(criteria: QueryCriteria): Int
    
    /**
     * 특정 시간 이전의 오래된 레코드를 삭제한다.
     * 
     * @param beforeTimestamp 기준 시간 (epoch milliseconds)
     * @param statuses 삭제할 상태 목록 (기본값: SUCCESS, FAILED, EXPIRED)
     * @return 삭제된 레코드 수
     */
    suspend fun deleteOldRecords(
        beforeTimestamp: Long,
        statuses: Set<DLQStatus> = setOf(DLQStatus.SUCCESS, DLQStatus.FAILED, DLQStatus.EXPIRED)
    ): Int
    
    /**
     * 저장소의 레코드 수를 조회한다.
     * 
     * @param status 특정 상태의 레코드만 카운트 (optional)
     * @return 레코드 수
     */
    suspend fun count(status: DLQStatus? = null): Long
    
    /**
     * 저장소의 통계 정보를 조회한다.
     * 
     * @return 저장소 통계 정보
     */
    suspend fun getStatistics(): StorageStatistics
    
    /**
     * 저장소 상태를 검증한다.
     * 연결 상태, 용량 등을 확인한다.
     * 
     * @return 저장소 상태가 정상이면 true
     */
    suspend fun isHealthy(): Boolean
    
    /**
     * 저장소를 초기화한다.
     * 테스트나 개발 환경에서 사용한다.
     */
    suspend fun clear()
}

/**
 * 저장소 통계 정보.
 * 
 * @property totalRecords 전체 레코드 수
 * @property recordsByStatus 상태별 레코드 수
 * @property oldestRecordTimestamp 가장 오래된 레코드의 시간
 * @property newestRecordTimestamp 가장 최근 레코드의 시간
 * @property averageRecordSize 평균 레코드 크기 (bytes)
 * @property totalStorageSize 전체 저장소 크기 (bytes, optional)
 */
data class StorageStatistics(
    val totalRecords: Long,
    val recordsByStatus: Map<DLQStatus, Long>,
    val oldestRecordTimestamp: Long?,
    val newestRecordTimestamp: Long?,
    val averageRecordSize: Long,
    val totalStorageSize: Long? = null
)