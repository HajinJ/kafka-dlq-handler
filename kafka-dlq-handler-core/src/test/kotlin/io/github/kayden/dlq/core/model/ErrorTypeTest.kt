package io.github.kayden.dlq.core.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

@DisplayName("ErrorType 테스트")
class ErrorTypeTest {
    
    @Nested
    @DisplayName("에러 타입 속성")
    inner class ErrorTypeProperties {
        
        @ParameterizedTest
        @CsvSource(
            "TRANSIENT, true, 5",
            "PERMANENT, false, 0",
            "TIMEOUT, true, 3",
            "SERIALIZATION, false, 1",
            "BUSINESS_LOGIC, false, 0",
            "INFRASTRUCTURE, true, 4",
            "RESOURCE_EXHAUSTED, true, 3",
            "UNKNOWN, true, 2"
        )
        fun `각 에러 타입의 재시도 가능 여부와 기본 재시도 횟수 확인`(
            errorTypeName: String,
            expectedRetryable: Boolean,
            expectedMaxRetries: Int
        ) {
            val errorType = ErrorType.valueOf(errorTypeName)
            
            assertThat(errorType.retryable).isEqualTo(expectedRetryable)
            assertThat(errorType.defaultMaxRetries).isEqualTo(expectedMaxRetries)
        }
        
        @Test
        fun `모든 에러 타입이 설명을 가지고 있음`() {
            ErrorType.values().forEach { errorType ->
                assertThat(errorType.description).isNotBlank()
            }
        }
    }
    
    @Nested
    @DisplayName("예외로부터 ErrorType 추론")
    inner class FromException {
        
        @Test
        fun `IO 관련 예외는 TRANSIENT로 분류`() {
            assertThat(ErrorType.fromException(IOException())).isEqualTo(ErrorType.TRANSIENT)
            assertThat(ErrorType.fromException(SocketException())).isEqualTo(ErrorType.TRANSIENT)
            assertThat(ErrorType.fromException(UnknownHostException())).isEqualTo(ErrorType.TRANSIENT)
        }
        
        @Test
        fun `타임아웃 예외는 TIMEOUT으로 분류`() {
            assertThat(ErrorType.fromException(TimeoutException())).isEqualTo(ErrorType.TIMEOUT)
        }
        
        @Test
        fun `IllegalArgumentException은 PERMANENT로 분류`() {
            assertThat(ErrorType.fromException(IllegalArgumentException())).isEqualTo(ErrorType.PERMANENT)
            assertThat(ErrorType.fromException(IllegalStateException())).isEqualTo(ErrorType.PERMANENT)
            assertThat(ErrorType.fromException(NullPointerException())).isEqualTo(ErrorType.PERMANENT)
        }
        
        @Test
        fun `메모리 관련 에러는 RESOURCE_EXHAUSTED로 분류`() {
            assertThat(ErrorType.fromException(OutOfMemoryError())).isEqualTo(ErrorType.RESOURCE_EXHAUSTED)
            assertThat(ErrorType.fromException(StackOverflowError())).isEqualTo(ErrorType.RESOURCE_EXHAUSTED)
        }
        
        @Test
        fun `알 수 없는 예외는 UNKNOWN으로 분류`() {
            assertThat(ErrorType.fromException(RuntimeException())).isEqualTo(ErrorType.UNKNOWN)
            assertThat(ErrorType.fromException(Exception())).isEqualTo(ErrorType.UNKNOWN)
        }
    }
    
    @Nested
    @DisplayName("에러 메시지로부터 ErrorType 추론")
    inner class FromErrorMessage {
        
        @ParameterizedTest
        @CsvSource(
            "Connection timeout occurred, TIMEOUT",
            "Request TIMEOUT after 30 seconds, TIMEOUT",
            "Connection refused, INFRASTRUCTURE",
            "Database connection failed, INFRASTRUCTURE",
            "Serialization error occurred, SERIALIZATION",
            "Failed to deserialize message, SERIALIZATION",
            "Out of memory error, RESOURCE_EXHAUSTED",
            "Insufficient memory available, RESOURCE_EXHAUSTED",
            "Validation failed for field, BUSINESS_LOGIC",
            "Business rule validation error, BUSINESS_LOGIC"
        )
        fun `에러 메시지의 키워드로 ErrorType 추론`(message: String, expectedType: String) {
            val errorType = ErrorType.fromErrorMessage(message)
            assertThat(errorType).isEqualTo(ErrorType.valueOf(expectedType))
        }
        
        @Test
        fun `null 메시지는 UNKNOWN으로 분류`() {
            assertThat(ErrorType.fromErrorMessage(null)).isEqualTo(ErrorType.UNKNOWN)
        }
        
        @Test
        fun `키워드가 없는 메시지는 UNKNOWN으로 분류`() {
            assertThat(ErrorType.fromErrorMessage("Some generic error")).isEqualTo(ErrorType.UNKNOWN)
        }
        
        @Test
        fun `대소문자 구분 없이 키워드 매칭`() {
            assertThat(ErrorType.fromErrorMessage("TIMEOUT error")).isEqualTo(ErrorType.TIMEOUT)
            assertThat(ErrorType.fromErrorMessage("timeout error")).isEqualTo(ErrorType.TIMEOUT)
            assertThat(ErrorType.fromErrorMessage("TiMeOuT error")).isEqualTo(ErrorType.TIMEOUT)
        }
    }
}