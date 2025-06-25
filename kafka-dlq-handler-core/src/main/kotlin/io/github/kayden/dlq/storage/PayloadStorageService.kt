package io.github.kayden.dlq.storage

/**
 * 페이로드 외부 저장소 인터페이스
 * 
 * 대용량 메시지를 외부 저장소(S3, MinIO 등)에 저장하기 위한 추상화
 */
interface PayloadStorageService {
    /**
     * 페이로드를 저장하고 위치를 반환
     */
    suspend fun store(messageId: String, payload: String): String
    
    /**
     * 저장된 페이로드 조회
     */
    suspend fun retrieve(location: String): String?
    
    /**
     * 페이로드 삭제
     */
    suspend fun delete(location: String)
    
    /**
     * 배치 삭제
     */
    suspend fun deleteBatch(locations: List<String>)
}