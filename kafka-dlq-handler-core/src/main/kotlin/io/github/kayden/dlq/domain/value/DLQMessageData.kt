package io.github.kayden.dlq.domain.value

import java.time.Instant

/**
 * 배치 저장용 메시지 데이터
 * 
 * JSON으로 직렬화되어 DLQMessageBatch에 압축 저장된다.
 */
data class DLQMessageData(
    val messageKey: String?,
    val payload: String,
    val headers: Map<String, String>,
    val errorClass: String?,
    val errorMessage: String?,
    val timestamp: Instant,
    val partitionId: Int,
    val offsetValue: Long
)