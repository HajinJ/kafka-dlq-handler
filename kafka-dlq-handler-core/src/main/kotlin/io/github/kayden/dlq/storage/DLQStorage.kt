package io.github.kayden.dlq.storage

import io.github.kayden.dlq.core.model.DLQRecord
import kotlinx.coroutines.flow.Flow

/**
 * DLQ 레코드의 저장소 인터페이스
 * 
 * 이 인터페이스는 프레임워크 독립적으로 설계되었으며,
 * 다양한 저장소 구현체(메모리, Redis, Cassandra 등)를 지원합니다.
 * 
 * 모든 작업은 suspend 함수로 정의되어 비동기 처리를 지원하며,
 * 대규모 트래픽 처리를 위한 배치 작업을 제공합니다.
 * 
 * 구현체는 다음을 보장해야 합니다:
 * - Thread-safe 작업
 * - 배치 작업의 원자성 (가능한 경우)
 * - 효율적인 쿼리 실행
 */
interface DLQStorage {
    
    // ========== 단일 작업 ==========
    
    /**
     * 단일 레코드 저장
     * 
     * @param record 저장할 DLQ 레코드
     * @return 저장 결과
     */
    suspend fun save(record: DLQRecord): StorageResult
    
    /**
     * ID로 레코드 조회
     * 
     * @param id 조회할 레코드 ID
     * @return 레코드 또는 null
     */
    suspend fun findById(id: String): DLQRecord?
    
    /**
     * ID로 레코드 삭제
     * 
     * @param id 삭제할 레코드 ID
     * @return 삭제 성공 여부
     */
    suspend fun deleteById(id: String): Boolean
    
    /**
     * 레코드 업데이트
     * 
     * @param record 업데이트할 레코드
     * @return 업데이트 결과
     */
    suspend fun update(record: DLQRecord): StorageResult
    
    // ========== 배치 작업 ==========
    
    /**
     * 배치 저장 - 고성능 대량 저장
     * 
     * 구현체는 가능한 한 트랜잭션으로 처리해야 하며,
     * 실패한 레코드에 대한 상세 정보를 제공해야 합니다.
     * 
     * @param records 저장할 레코드 목록
     * @return 배치 저장 결과
     */
    suspend fun saveBatch(records: List<DLQRecord>): BatchStorageResult
    
    /**
     * ID 목록으로 배치 조회
     * 
     * 존재하지 않는 ID는 결과에서 제외됩니다.
     * 
     * @param ids 조회할 ID 목록
     * @return 찾은 레코드 목록
     */
    suspend fun findBatch(ids: List<String>): List<DLQRecord>
    
    /**
     * ID 목록으로 배치 삭제
     * 
     * @param ids 삭제할 ID 목록
     * @return 실제로 삭제된 레코드 수
     */
    suspend fun deleteBatch(ids: List<String>): Int
    
    // ========== 쿼리 작업 ==========
    
    /**
     * 조건에 따른 레코드 조회
     * 
     * @param criteria 쿼리 조건
     * @return 조건에 맞는 레코드 목록
     */
    suspend fun find(criteria: QueryCriteria): List<DLQRecord>
    
    /**
     * 조건에 맞는 레코드 수 조회
     * 
     * @param criteria 쿼리 조건 (limit, offset은 무시됨)
     * @return 조건에 맞는 레코드 수
     */
    suspend fun count(criteria: QueryCriteria): Long
    
    /**
     * 조건에 맞는 레코드 존재 여부 확인
     * 
     * @param criteria 쿼리 조건
     * @return 레코드 존재 여부
     */
    suspend fun exists(criteria: QueryCriteria): Boolean = count(criteria) > 0
    
    // ========== 스트리밍 작업 ==========
    
    /**
     * 조건에 따른 레코드 스트리밍
     * 
     * 대량의 데이터를 메모리 효율적으로 처리하기 위한 스트리밍 API입니다.
     * 구현체는 lazy loading과 pagination을 활용해야 합니다.
     * 
     * @param criteria 쿼리 조건
     * @return 레코드의 Flow
     */
    fun stream(criteria: QueryCriteria): Flow<DLQRecord>
    
    /**
     * 모든 레코드 스트리밍
     * 
     * 주의: 대량의 데이터가 있을 수 있으므로 신중히 사용해야 합니다.
     * 
     * @return 모든 레코드의 Flow
     */
    fun streamAll(): Flow<DLQRecord> = stream(QueryCriteria(limit = Int.MAX_VALUE))
    
    // ========== 벌크 작업 ==========
    
    /**
     * 조건에 맞는 레코드 일괄 업데이트
     * 
     * @param criteria 업데이트할 레코드 조건
     * @param update 업데이트 함수
     * @return 업데이트된 레코드 수
     */
    suspend fun bulkUpdate(
        criteria: QueryCriteria,
        update: (DLQRecord) -> DLQRecord
    ): Int
    
    /**
     * 조건에 맞는 레코드 일괄 삭제
     * 
     * @param criteria 삭제할 레코드 조건
     * @return 삭제된 레코드 수
     */
    suspend fun bulkDelete(criteria: QueryCriteria): Int
    
    // ========== 관리 작업 ==========
    
    /**
     * 전체 레코드 수
     * 
     * @return 총 레코드 수
     */
    suspend fun totalCount(): Long
    
    /**
     * 모든 레코드 삭제
     * 
     * 주의: 이 작업은 되돌릴 수 없습니다.
     */
    suspend fun clear()
    
    /**
     * 저장소 최적화
     * 
     * 구현체별로 다른 최적화 작업을 수행합니다.
     * 예: 인덱스 재구성, 메모리 정리, 압축 등
     */
    suspend fun optimize() {
        // 기본 구현: 아무 작업도 하지 않음
    }
}