package io.github.kayden.dlq.core.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

class ErrorTypeTest {
    
    @Test
    @DisplayName("재시도 가능한 에러 타입을 올바르게 식별한다")
    fun shouldIdentifyRetryableErrorTypes() {
        assertTrue(ErrorType.TRANSIENT_NETWORK_ERROR.isRetryable())
        assertTrue(ErrorType.TRANSIENT_SERVICE_ERROR.isRetryable())
        assertTrue(ErrorType.RESOURCE_EXHAUSTED.isRetryable())
        assertTrue(ErrorType.AUTHENTICATION_ERROR.isRetryable())
        assertTrue(ErrorType.UNKNOWN.isRetryable())
        
        assertFalse(ErrorType.PERMANENT_FAILURE.isRetryable())
        assertFalse(ErrorType.SERIALIZATION_ERROR.isRetryable())
        assertFalse(ErrorType.VALIDATION_ERROR.isRetryable())
    }
    
    @Test
    @DisplayName("일시적 에러 타입을 올바르게 식별한다")
    fun shouldIdentifyTransientErrorTypes() {
        assertTrue(ErrorType.TRANSIENT_NETWORK_ERROR.isTransient())
        assertTrue(ErrorType.TRANSIENT_SERVICE_ERROR.isTransient())
        assertTrue(ErrorType.RESOURCE_EXHAUSTED.isTransient())
        
        assertFalse(ErrorType.PERMANENT_FAILURE.isTransient())
        assertFalse(ErrorType.SERIALIZATION_ERROR.isTransient())
        assertFalse(ErrorType.VALIDATION_ERROR.isTransient())
        assertFalse(ErrorType.AUTHENTICATION_ERROR.isTransient())
        assertFalse(ErrorType.UNKNOWN.isTransient())
    }
    
    @Test
    @DisplayName("영구적 에러 타입을 올바르게 식별한다")
    fun shouldIdentifyPermanentErrorTypes() {
        assertTrue(ErrorType.PERMANENT_FAILURE.isPermanent())
        assertTrue(ErrorType.SERIALIZATION_ERROR.isPermanent())
        assertTrue(ErrorType.VALIDATION_ERROR.isPermanent())
        
        assertFalse(ErrorType.TRANSIENT_NETWORK_ERROR.isPermanent())
        assertFalse(ErrorType.TRANSIENT_SERVICE_ERROR.isPermanent())
        assertFalse(ErrorType.RESOURCE_EXHAUSTED.isPermanent())
        assertFalse(ErrorType.AUTHENTICATION_ERROR.isPermanent())
        assertFalse(ErrorType.UNKNOWN.isPermanent())
    }
    
    @Test
    @DisplayName("에러 타입별 권장 백오프 전략을 올바르게 반환한다")
    fun shouldReturnCorrectBackoffStrategy() {
        assertEquals(
            BackoffStrategy.EXPONENTIAL,
            ErrorType.TRANSIENT_NETWORK_ERROR.recommendedBackoffStrategy()
        )
        assertEquals(
            BackoffStrategy.EXPONENTIAL_WITH_JITTER,
            ErrorType.TRANSIENT_SERVICE_ERROR.recommendedBackoffStrategy()
        )
        assertEquals(
            BackoffStrategy.LINEAR,
            ErrorType.RESOURCE_EXHAUSTED.recommendedBackoffStrategy()
        )
        assertEquals(
            BackoffStrategy.FIXED,
            ErrorType.AUTHENTICATION_ERROR.recommendedBackoffStrategy()
        )
        assertEquals(
            BackoffStrategy.NONE,
            ErrorType.PERMANENT_FAILURE.recommendedBackoffStrategy()
        )
        assertEquals(
            BackoffStrategy.NONE,
            ErrorType.SERIALIZATION_ERROR.recommendedBackoffStrategy()
        )
        assertEquals(
            BackoffStrategy.NONE,
            ErrorType.VALIDATION_ERROR.recommendedBackoffStrategy()
        )
        assertEquals(
            BackoffStrategy.NONE,
            ErrorType.UNKNOWN.recommendedBackoffStrategy()
        )
    }
    
    @ParameterizedTest
    @CsvSource(
        "java.net.SocketTimeoutException, TRANSIENT_NETWORK_ERROR",
        "java.net.ConnectException, TRANSIENT_NETWORK_ERROR",
        "org.apache.kafka.common.errors.NetworkException, TRANSIENT_NETWORK_ERROR",
        "io.grpc.StatusRuntimeException, TRANSIENT_SERVICE_ERROR",
        "javax.ws.rs.ServiceUnavailableException, TRANSIENT_SERVICE_ERROR",
        "com.fasterxml.jackson.core.JsonParseException, SERIALIZATION_ERROR",
        "java.io.NotSerializableException, SERIALIZATION_ERROR",
        "org.springframework.security.access.AccessDeniedException, AUTHENTICATION_ERROR",
        "javax.security.auth.login.LoginException, AUTHENTICATION_ERROR",
        "java.lang.SecurityException, AUTHENTICATION_ERROR",
        "javax.validation.ValidationException, VALIDATION_ERROR",
        "java.lang.IllegalArgumentException, VALIDATION_ERROR",
        "java.lang.OutOfMemoryError, RESOURCE_EXHAUSTED",
        "java.lang.RuntimeException, UNKNOWN",
        ", UNKNOWN"
    )
    @DisplayName("예외 클래스명으로부터 에러 타입을 올바르게 추론한다")
    fun shouldInferErrorTypeFromException(exceptionClassName: String?, expectedType: ErrorType) {
        assertEquals(expectedType, ErrorType.fromException(exceptionClassName))
    }
    
    @Test
    @DisplayName("대소문자를 무시하고 예외 클래스명을 매칭한다")
    fun shouldMatchExceptionNameIgnoringCase() {
        assertEquals(
            ErrorType.TRANSIENT_NETWORK_ERROR,
            ErrorType.fromException("com.example.NetworkTimeoutException")
        )
        assertEquals(
            ErrorType.TRANSIENT_NETWORK_ERROR,
            ErrorType.fromException("com.example.NETWORKTIMEOUTEXCEPTION")
        )
        assertEquals(
            ErrorType.AUTHENTICATION_ERROR,
            ErrorType.fromException("com.example.AuthenticationFailedException")
        )
    }
    
    @Test
    @DisplayName("companion object 에러 타입 집합이 올바르게 정의되어 있다")
    fun shouldHaveCorrectErrorTypeCollections() {
        assertEquals(
            setOf(
                ErrorType.TRANSIENT_NETWORK_ERROR,
                ErrorType.TRANSIENT_SERVICE_ERROR,
                ErrorType.RESOURCE_EXHAUSTED,
                ErrorType.AUTHENTICATION_ERROR,
                ErrorType.UNKNOWN
            ),
            ErrorType.retryableTypes
        )
        
        assertEquals(
            setOf(
                ErrorType.TRANSIENT_NETWORK_ERROR,
                ErrorType.TRANSIENT_SERVICE_ERROR,
                ErrorType.RESOURCE_EXHAUSTED
            ),
            ErrorType.transientTypes
        )
        
        assertEquals(
            setOf(
                ErrorType.PERMANENT_FAILURE,
                ErrorType.SERIALIZATION_ERROR,
                ErrorType.VALIDATION_ERROR
            ),
            ErrorType.permanentTypes
        )
    }
    
    @ParameterizedTest
    @EnumSource(ErrorType::class)
    @DisplayName("모든 에러 타입은 상호 배타적인 카테고리에 속한다")
    fun shouldBelongToMutuallyExclusiveCategories(errorType: ErrorType) {
        val isTransient = errorType.isTransient()
        val isPermanent = errorType.isPermanent()
        val isOther = !isTransient && !isPermanent
        
        val categoriesCount = listOf(isTransient, isPermanent, isOther).count { it }
        assertEquals(
            1,
            categoriesCount,
            "$errorType should belong to exactly one category"
        )
    }
    
    @Test
    @DisplayName("재시도 가능한 타입은 일시적이거나 기타 카테고리에 속한다")
    fun shouldHaveRetryableTypesInTransientOrOther() {
        for (errorType in ErrorType.values()) {
            if (errorType.isRetryable()) {
                assertTrue(
                    errorType.isTransient() || 
                    (!errorType.isTransient() && !errorType.isPermanent()),
                    "$errorType is retryable but not in transient or other category"
                )
            }
        }
    }
    
    @Test
    @DisplayName("영구적 에러는 재시도 불가능하다")
    fun shouldNotRetryPermanentErrors() {
        for (errorType in ErrorType.values()) {
            if (errorType.isPermanent()) {
                assertFalse(
                    errorType.isRetryable(),
                    "$errorType is permanent but marked as retryable"
                )
            }
        }
    }
}