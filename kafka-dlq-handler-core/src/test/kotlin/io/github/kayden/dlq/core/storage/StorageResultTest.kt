package io.github.kayden.dlq.core.storage

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.DLQStatus
import io.github.kayden.dlq.core.model.ErrorType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class StorageResultTest {
    
    @Test
    fun shouldCreateSuccessResult() {
        val record = createTestRecord()
        val result = StorageResult.Success(
            recordId = "test-id",
            record = record,
            message = "Operation successful"
        )
        
        assertEquals("test-id", result.recordId)
        assertEquals(record, result.record)
        assertEquals("Operation successful", result.message)
        assertTrue(result.isSuccess())
        assertFalse(result.isFailure())
    }
    
    @Test
    
    fun shouldCreateFailureResult() {
        val exception = RuntimeException("Test error")
        val result = StorageResult.Failure(
            recordId = "test-id",
            errorType = StorageErrorType.CONNECTION_ERROR,
            message = "Connection failed",
            exception = exception
        )
        
        assertEquals("test-id", result.recordId)
        assertEquals(StorageErrorType.CONNECTION_ERROR, result.errorType)
        assertEquals("Connection failed", result.message)
        assertEquals(exception, result.exception)
        assertFalse(result.isSuccess())
        assertTrue(result.isFailure())
    }
    
    @Test
    
    fun shouldCreateNotFoundResult() {
        val result = StorageResult.NotFound(
            recordId = "missing-id",
            message = "Custom not found message"
        )
        
        assertEquals("missing-id", result.recordId)
        assertEquals("Custom not found message", result.message)
        assertFalse(result.isSuccess())
        assertTrue(result.isFailure())
    }
    
    @Test
    
    fun shouldCreateConcurrencyConflictResult() {
        val result = StorageResult.ConcurrencyConflict(
            recordId = "test-id",
            currentVersion = 5,
            expectedVersion = 3,
            message = "Version mismatch"
        )
        
        assertEquals("test-id", result.recordId)
        assertEquals(5, result.currentVersion)
        assertEquals(3, result.expectedVersion)
        assertEquals("Version mismatch", result.message)
        assertFalse(result.isSuccess())
        assertTrue(result.isFailure())
    }
    
    @Test
    
    fun shouldReturnRecordOnSuccess() {
        val record = createTestRecord()
        val success = StorageResult.Success("id", record)
        assertEquals(record, success.getOrNull())
        
        val failure = StorageResult.Failure("id", StorageErrorType.UNKNOWN, "error")
        assertNull(failure.getOrNull())
        
        val notFound = StorageResult.NotFound("id")
        assertNull(notFound.getOrNull())
    }
    
    @Test
    
    fun shouldThrowOnFailure() {
        val record = createTestRecord()
        val success = StorageResult.Success("id", record)
        assertEquals(record, success.getOrThrow())
        
        val failure = StorageResult.Failure("id", StorageErrorType.UNKNOWN, "error message")
        val failureException = assertFailsWith<StorageException> {
            failure.getOrThrow()
        }
        assertEquals("error message", failureException.message)
        
        val notFound = StorageResult.NotFound("id", "Not found message")
        val notFoundException = assertFailsWith<NoSuchElementException> {
            notFound.getOrThrow()
        }
        assertEquals("Not found message", notFoundException.message)
        
        val conflict = StorageResult.ConcurrencyConflict("id", 2, 1, "Conflict message")
        val conflictException = assertFailsWith<StorageException> {
            conflict.getOrThrow()
        }
        assertEquals("Conflict message", conflictException.message)
    }
    
    @Test
    
    fun shouldExecuteOnSuccess() {
        var executed = false
        val record = createTestRecord()
        val success = StorageResult.Success("id", record)
        
        val result = success.onSuccess { 
            executed = true
            assertEquals("id", it.recordId)
        }
        
        assertTrue(executed)
        assertEquals(success, result) // 체이닝 가능
        
        // 실패 케이스
        executed = false
        val failure = StorageResult.Failure("id", StorageErrorType.UNKNOWN, "error")
        failure.onSuccess { executed = true }
        
        assertFalse(executed)
    }
    
    @Test
    
    fun shouldExecuteOnFailure() {
        var executed = false
        val failure = StorageResult.Failure("id", StorageErrorType.TIMEOUT, "timeout")
        
        val result = failure.onFailure { failureResult ->
            executed = true
            assertTrue(failureResult is StorageResult.Failure)
        }
        
        assertTrue(executed)
        assertEquals(failure, result) // 체이닝 가능
        
        // 성공 케이스
        executed = false
        val success = StorageResult.Success("id")
        success.onFailure { executed = true }
        
        assertFalse(executed)
    }
    
    @Test
    
    fun shouldRecoverOnFailure() {
        val failure = StorageResult.Failure("id", StorageErrorType.TIMEOUT, "timeout")
        
        val recovered = failure.recover { _ ->
            StorageResult.Success("id", message = "Recovered")
        }
        
        assertTrue(recovered is StorageResult.Success)
        assertEquals("Recovered", (recovered as StorageResult.Success).message)
        
        // 성공 케이스는 recover 실행하지 않음
        val record = createTestRecord()
        val success = StorageResult.Success("id", record)
        val notRecovered = success.recover { _ ->
            StorageResult.Failure("id", StorageErrorType.UNKNOWN, "Should not happen")
        }
        
        assertTrue(notRecovered is StorageResult.Success)
        assertEquals(record, (notRecovered as StorageResult.Success).record)
    }
    
    @Test
    
    fun shouldCalculateBatchStatistics() {
        val results = listOf(
            StorageResult.Success("id1"),
            StorageResult.Success("id2"),
            StorageResult.Failure("id3", StorageErrorType.VALIDATION_ERROR, "Invalid"),
            StorageResult.NotFound("id4"),
            StorageResult.Success("id5")
        )
        
        val batch = BatchStorageResult(results)
        
        assertEquals(5, results.size)
        assertEquals(3, batch.successCount)
        assertEquals(2, batch.failureCount)
        assertFalse(batch.isFullSuccess)
        assertTrue(batch.isPartialSuccess)
        assertFalse(batch.isFullFailure)
        
        val successes = batch.successes()
        assertEquals(3, successes.size)
        assertTrue(successes.all { it.recordId in listOf("id1", "id2", "id5") })
        
        val failures = batch.failures()
        assertEquals(2, failures.size)
        
        val successIds = batch.successfulIds()
        assertEquals(listOf("id1", "id2", "id5"), successIds)
        
        val failedIds = batch.failedIds()
        assertEquals(listOf("id3", "id4"), failedIds)
    }
    
    @Test
    
    fun shouldHandleFullSuccessAndFailure() {
        // 완전 성공
        val allSuccess = BatchStorageResult(listOf(
            StorageResult.Success("id1"),
            StorageResult.Success("id2"),
            StorageResult.Success("id3")
        ))
        
        assertTrue(allSuccess.isFullSuccess)
        assertFalse(allSuccess.isPartialSuccess)
        assertFalse(allSuccess.isFullFailure)
        assertEquals(3, allSuccess.successCount)
        assertEquals(0, allSuccess.failureCount)
        
        // 완전 실패
        val allFailure = BatchStorageResult(listOf(
            StorageResult.Failure("id1", StorageErrorType.UNKNOWN, "error"),
            StorageResult.NotFound("id2"),
            StorageResult.ConcurrencyConflict("id3", 2, 1)
        ))
        
        assertFalse(allFailure.isFullSuccess)
        assertFalse(allFailure.isPartialSuccess)
        assertTrue(allFailure.isFullFailure)
        assertEquals(0, allFailure.successCount)
        assertEquals(3, allFailure.failureCount)
    }
    
    @Test
    
    fun shouldHaveAllErrorTypes() {
        val errorTypes = StorageErrorType.values()
        
        assertTrue(errorTypes.contains(StorageErrorType.CONNECTION_ERROR))
        assertTrue(errorTypes.contains(StorageErrorType.TIMEOUT))
        assertTrue(errorTypes.contains(StorageErrorType.VALIDATION_ERROR))
        assertTrue(errorTypes.contains(StorageErrorType.CAPACITY_EXCEEDED))
        assertTrue(errorTypes.contains(StorageErrorType.ACCESS_DENIED))
        assertTrue(errorTypes.contains(StorageErrorType.SERIALIZATION_ERROR))
        assertTrue(errorTypes.contains(StorageErrorType.TRANSACTION_ERROR))
        assertTrue(errorTypes.contains(StorageErrorType.UNKNOWN))
        
        assertEquals(8, errorTypes.size)
    }
    
    @Test
    
    fun shouldCreateStorageExceptionWithCause() {
        val cause = RuntimeException("Original error")
        val exception = StorageException("Storage operation failed", cause)
        
        assertEquals("Storage operation failed", exception.message)
        assertEquals(cause, exception.cause)
        
        // 원인 없는 경우
        val exceptionNoCause = StorageException("Simple error")
        assertEquals("Simple error", exceptionNoCause.message)
        assertNull(exceptionNoCause.cause)
    }
    
    private fun createTestRecord(): DLQRecord {
        return DLQRecord(
            messageKey = "test-key",
            originalTopic = "test-topic",
            originalPartition = 0,
            originalOffset = 123L,
            payload = "test payload".toByteArray(),
            headers = emptyMap(),
            errorClass = "TestException",
            errorMessage = "Test error",
            errorType = ErrorType.TRANSIENT_NETWORK_ERROR,
            stackTrace = null,
            retryCount = 0,
            createdAt = System.currentTimeMillis()
        )
    }
}