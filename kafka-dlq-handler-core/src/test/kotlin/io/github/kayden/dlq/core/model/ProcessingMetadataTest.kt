package io.github.kayden.dlq.core.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

@DisplayName("ProcessingMetadata 테스트")
class ProcessingMetadataTest {
    
    @Nested
    @DisplayName("ProcessingMetadata")
    inner class ProcessingMetadataTests {
        
        @Test
        fun `기본 생성자로 빈 메타데이터 생성`() {
            val metadata = ProcessingMetadata()
            
            assertThat(metadata.processingAttempts).isEmpty()
            assertThat(metadata.tags).isEmpty()
            assertThat(metadata.customAttributes).isEmpty()
            assertThat(metadata.processorInfo).isNull()
            assertThat(metadata.retryPolicy).isNull()
        }
        
        @Test
        fun `태그 추가 기능`() {
            val metadata = ProcessingMetadata()
            val updated = metadata.withTag("tag1").withTag("tag2")
            
            assertThat(updated.tags).containsExactlyInAnyOrder("tag1", "tag2")
        }
        
        @Test
        fun `여러 태그 한번에 추가`() {
            val metadata = ProcessingMetadata(tags = setOf("existing"))
            val updated = metadata.withTags(setOf("new1", "new2"))
            
            assertThat(updated.tags).containsExactlyInAnyOrder("existing", "new1", "new2")
        }
        
        @Test
        fun `커스텀 속성 추가`() {
            val metadata = ProcessingMetadata()
            val updated = metadata
                .withAttribute("key1", "value1")
                .withAttribute("key2", "value2")
            
            assertThat(updated.customAttributes).containsExactlyInAnyOrderEntriesOf(
                mapOf("key1" to "value1", "key2" to "value2")
            )
        }
        
        @Test
        fun `처리 시도 추가 및 통계`() {
            val attempt1 = ProcessingAttempt(1, System.currentTimeMillis(), true)
            val attempt2 = ProcessingAttempt(2, System.currentTimeMillis(), false, "Error")
            val attempt3 = ProcessingAttempt(3, System.currentTimeMillis(), true)
            
            val metadata = ProcessingMetadata()
                .withProcessingAttempt(attempt1)
                .withProcessingAttempt(attempt2)
                .withProcessingAttempt(attempt3)
            
            assertThat(metadata.totalAttempts).isEqualTo(3)
            assertThat(metadata.successfulAttempts).isEqualTo(2)
            assertThat(metadata.lastAttempt).isEqualTo(attempt3)
        }
    }
    
    @Nested
    @DisplayName("ProcessingAttempt")
    inner class ProcessingAttemptTests {
        
        @Test
        fun `유효한 처리 시도 생성`() {
            val attempt = ProcessingAttempt(
                attemptNumber = 1,
                timestamp = System.currentTimeMillis(),
                successful = true,
                processingDuration = 100L,
                processorId = "processor-1"
            )
            
            assertThat(attempt.attemptNumber).isEqualTo(1)
            assertThat(attempt.successful).isTrue()
            assertThat(attempt.errorMessage).isNull()
        }
        
        @Test
        fun `시도 번호가 0 이하일 때 예외 발생`() {
            assertThatThrownBy {
                ProcessingAttempt(0, System.currentTimeMillis(), true)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Attempt number must be positive")
        }
        
        @Test
        fun `타임스탬프가 0 이하일 때 예외 발생`() {
            assertThatThrownBy {
                ProcessingAttempt(1, 0, true)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Timestamp must be positive")
        }
        
        @Test
        fun `성공한 시도에 에러 메시지가 있으면 예외 발생`() {
            assertThatThrownBy {
                ProcessingAttempt(1, System.currentTimeMillis(), true, "Error message")
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Successful attempt should not have error message")
        }
        
        @Test
        fun `처리 시간이 음수면 예외 발생`() {
            assertThatThrownBy {
                ProcessingAttempt(1, System.currentTimeMillis(), false, "Error", -1L)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Processing duration must not be negative")
        }
    }
    
    @Nested
    @DisplayName("ProcessorInfo")
    inner class ProcessorInfoTests {
        
        @Test
        fun `유효한 처리기 정보 생성`() {
            val info = ProcessorInfo(
                processorId = "processor-1",
                processorType = "BatchProcessor",
                version = "1.0.0",
                hostname = "host-1"
            )
            
            assertThat(info.processorId).isEqualTo("processor-1")
            assertThat(info.processorType).isEqualTo("BatchProcessor")
            assertThat(info.version).isEqualTo("1.0.0")
            assertThat(info.hostname).isEqualTo("host-1")
        }
        
        @Test
        fun `필수 필드가 비어있으면 예외 발생`() {
            assertThatThrownBy {
                ProcessorInfo("", "type", "1.0")
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Processor ID must not be blank")
            
            assertThatThrownBy {
                ProcessorInfo("id", "", "1.0")
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Processor type must not be blank")
            
            assertThatThrownBy {
                ProcessorInfo("id", "type", "")
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Version must not be blank")
        }
    }
    
}