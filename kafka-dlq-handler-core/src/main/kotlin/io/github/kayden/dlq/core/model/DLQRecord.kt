package io.github.kayden.dlq.core.model

import java.time.Instant

/**
 * Core DLQ record data class
 */
data class DLQRecord(
    val id: String,
    val messageKey: String?,
    val originalTopic: String,
    val originalPartition: Int,
    val originalOffset: Long,
    val payload: ByteArray,
    val headers: Map<String, ByteArray>,
    val errorClass: String?,
    val errorMessage: String?,
    val errorType: ErrorType,
    val stackTrace: String?,
    val status: DLQStatus = DLQStatus.PENDING,
    val retryCount: Int = 0,
    val lastRetryAt: Long? = null,
    val processingMetadata: ProcessingMetadata? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DLQRecord) return false
        return id == other.id
    }
    
    override fun hashCode(): Int = id.hashCode()
}

/**
 * DLQ status enumeration
 */
enum class DLQStatus {
    PENDING,
    RETRYING,
    SUCCESS,
    FAILED,
    EXPIRED,
    SKIPPED,
    ON_HOLD
}

/**
 * Error type enumeration
 */
enum class ErrorType {
    TRANSIENT,
    PERMANENT,
    TIMEOUT,
    SERIALIZATION,
    BUSINESS_LOGIC,
    INFRASTRUCTURE,
    RESOURCE_EXHAUSTED,
    UNKNOWN
}

/**
 * Processing metadata
 */
data class ProcessingMetadata(
    val processorId: String,
    val processingTime: Long,
    val attemptNumber: Int
)