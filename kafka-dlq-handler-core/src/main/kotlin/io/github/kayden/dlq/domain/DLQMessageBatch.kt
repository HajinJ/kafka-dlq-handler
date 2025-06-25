package io.github.kayden.dlq.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * DLQ 메시지 배치 (대량 처리용)
 * 
 * 여러 메시지를 하나의 레코드로 묶어서 저장하여 
 * 대량 트래픽 처리 시 DB 부하를 줄인다.
 */
@Entity
@Table(
    name = "dlq_message_batch",
    indexes = [
        Index(name = "idx_batch_time", columnList = "batch_time"),
        Index(name = "idx_topic", columnList = "topic")
    ]
)
data class DLQMessageBatch(
    @Id
    val batchId: String,
    
    @Column(name = "topic", nullable = false)
    val topic: String,
    
    @Column(name = "batch_time", nullable = false)
    val batchTime: Instant = Instant.now(),
    
    @Column(name = "message_count")
    val messageCount: Int,
    
    @Column(name = "total_size")
    val totalSize: Long,  // 전체 크기 (bytes)
    
    @Column(name = "compressed_data", columnDefinition = "BYTEA")
    val compressedData: ByteArray,  // 압축된 메시지 배치
    
    @Column(name = "compression_type", length = 20)
    val compressionType: String = "GZIP",
    
    @Column(name = "checksum")
    val checksum: String,  // 데이터 무결성 검증용
    
    // 파티셔닝용 필드
    @Column(name = "batch_date", nullable = false)
    val batchDate: String = batchTime.toString().substring(0, 10)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as DLQMessageBatch
        
        return batchId == other.batchId
    }
    
    override fun hashCode(): Int {
        return batchId.hashCode()
    }
}