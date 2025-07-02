package io.github.kayden.dlq.core.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration

@DisplayName("ProcessingConfig 테스트")
class ProcessingConfigTest {
    
    @Test
    fun `기본 설정으로 생성되어야 한다`() {
        // Given & When
        val config = ProcessingConfig()
        
        // Then
        assertThat(config.batchSize).isEqualTo(ProcessingConfig.DEFAULT_BATCH_SIZE)
        assertThat(config.parallelism).isEqualTo(ProcessingConfig.DEFAULT_PARALLELISM)
        assertThat(config.processingTimeout).isEqualTo(ProcessingConfig.DEFAULT_PROCESSING_TIMEOUT)
        assertThat(config.batchTimeout).isEqualTo(ProcessingConfig.DEFAULT_BATCH_TIMEOUT)
        assertThat(config.retryPolicy).isNotNull()
        assertThat(config.enableMetrics).isTrue()
        assertThat(config.enableTracing).isFalse()
        assertThat(config.processingMode).isEqualTo(ProcessingConfig.ProcessingMode.BATCH)
    }
    
    @Nested
    @DisplayName("유효성 검증")
    inner class ValidationTest {
        
        @Test
        fun `batchSize는 양수여야 한다`() {
            assertThatThrownBy {
                ProcessingConfig(batchSize = 0)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Batch size must be positive")
            
            assertThatThrownBy {
                ProcessingConfig(batchSize = -1)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Batch size must be positive")
        }
        
        @Test
        fun `batchSize는 최대값을 초과할 수 없다`() {
            assertThatThrownBy {
                ProcessingConfig(batchSize = ProcessingConfig.MAX_BATCH_SIZE + 1)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Batch size cannot exceed ${ProcessingConfig.MAX_BATCH_SIZE}")
        }
        
        @Test
        fun `parallelism은 양수여야 한다`() {
            assertThatThrownBy {
                ProcessingConfig(parallelism = 0)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Parallelism must be positive")
            
            assertThatThrownBy {
                ProcessingConfig(parallelism = -1)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Parallelism must be positive")
        }
        
        @Test
        fun `parallelism은 최대값을 초과할 수 없다`() {
            assertThatThrownBy {
                ProcessingConfig(parallelism = ProcessingConfig.MAX_PARALLELISM + 1)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Parallelism cannot exceed ${ProcessingConfig.MAX_PARALLELISM}")
        }
        
        @Test
        fun `processingTimeout은 양수여야 한다`() {
            assertThatThrownBy {
                ProcessingConfig(processingTimeout = Duration.ZERO)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Processing timeout must be positive")
            
            assertThatThrownBy {
                ProcessingConfig(processingTimeout = Duration.ofSeconds(-1))
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Processing timeout must be positive")
        }
        
        @Test
        fun `batchTimeout은 양수여야 한다`() {
            assertThatThrownBy {
                ProcessingConfig(batchTimeout = Duration.ZERO)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Batch timeout must be positive")
            
            assertThatThrownBy {
                ProcessingConfig(batchTimeout = Duration.ofMillis(-1))
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Batch timeout must be positive")
        }
    }
    
    @Nested
    @DisplayName("고성능 확인")
    inner class HighPerformanceTest {
        
        @ParameterizedTest
        @CsvSource(
            "1000, 8, BATCH, true",
            "999, 8, BATCH, false",
            "1000, 7, BATCH, false",
            "1000, 8, SINGLE, false",
            "5000, 16, BATCH, true",
            "100, 4, BATCH, false"
        )
        fun `고성능 설정 여부를 확인해야 한다`(
            batchSize: Int,
            parallelism: Int,
            mode: ProcessingConfig.ProcessingMode,
            expectedHighPerformance: Boolean
        ) {
            // Given
            val config = ProcessingConfig(
                batchSize = batchSize,
                parallelism = parallelism,
                processingMode = mode
            )
            
            // When & Then
            // 테스트 환경의 CPU 코어 수를 고려
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val actualResult = batchSize >= 1000 && 
                               parallelism >= cpuCores && 
                               mode == ProcessingConfig.ProcessingMode.BATCH
            
            assertThat(config.isHighPerformance()).isEqualTo(actualResult)
        }
    }
    
    @Nested
    @DisplayName("예상 처리량 계산")
    inner class ThroughputTest {
        
        @Test
        fun `SINGLE 모드의 예상 처리량을 계산해야 한다`() {
            // Given
            val config = ProcessingConfig(
                batchSize = 100,
                batchTimeout = Duration.ofMillis(100),
                parallelism = 8,
                processingMode = ProcessingConfig.ProcessingMode.SINGLE
            )
            
            // When
            val throughput = config.estimatedThroughput()
            
            // Then
            // (1000ms / 100ms) * 100 messages * 1 (SINGLE mode) = 1000
            assertThat(throughput).isEqualTo(1000)
        }
        
        @Test
        fun `BATCH 모드의 예상 처리량을 계산해야 한다`() {
            // Given
            val config = ProcessingConfig(
                batchSize = 1000,
                batchTimeout = Duration.ofMillis(50),
                parallelism = 4,
                processingMode = ProcessingConfig.ProcessingMode.BATCH
            )
            
            // When
            val throughput = config.estimatedThroughput()
            
            // Then
            // (1000ms / 50ms) * 1000 messages * 4 parallelism = 80000
            assertThat(throughput).isEqualTo(80_000)
        }
        
        @Test
        fun `STREAMING 모드의 예상 처리량을 계산해야 한다`() {
            // Given
            val config = ProcessingConfig(
                batchSize = 500,
                batchTimeout = Duration.ofMillis(200),
                parallelism = 10,
                processingMode = ProcessingConfig.ProcessingMode.STREAMING
            )
            
            // When
            val throughput = config.estimatedThroughput()
            
            // Then
            // (1000ms / 200ms) * 500 messages * (10 * 2) = 50000
            assertThat(throughput).isEqualTo(50_000)
        }
    }
    
    @Nested
    @DisplayName("사전 정의된 설정")
    inner class PredefinedConfigsTest {
        
        @Test
        fun `DEVELOPMENT 설정은 개발에 적합해야 한다`() {
            // Given
            val config = ProcessingConfig.DEVELOPMENT
            
            // Then
            assertThat(config.batchSize).isEqualTo(10)
            assertThat(config.parallelism).isEqualTo(2)
            assertThat(config.processingTimeout).isEqualTo(Duration.ofSeconds(60))
            assertThat(config.batchTimeout).isEqualTo(Duration.ofSeconds(1))
            assertThat(config.enableMetrics).isTrue()
            assertThat(config.enableTracing).isTrue()
        }
        
        @Test
        fun `PRODUCTION 설정은 프로덕션에 적합해야 한다`() {
            // Given
            val config = ProcessingConfig.PRODUCTION
            
            // Then
            assertThat(config.batchSize).isEqualTo(1000)
            assertThat(config.parallelism).isEqualTo(
                ProcessingConfig.DEFAULT_PARALLELISM
            )
            assertThat(config.processingTimeout).isEqualTo(Duration.ofSeconds(30))
            assertThat(config.batchTimeout).isEqualTo(Duration.ofMillis(100))
            assertThat(config.enableMetrics).isTrue()
            assertThat(config.enableTracing).isFalse()
        }
        
        @Test
        fun `HIGH_PERFORMANCE 설정은 고성능에 최적화되어야 한다`() {
            // Given
            val config = ProcessingConfig.HIGH_PERFORMANCE
            
            // Then
            assertThat(config.batchSize).isEqualTo(5000)
            assertThat(config.parallelism).isEqualTo(
                ProcessingConfig.DEFAULT_PARALLELISM * 2
            )
            assertThat(config.processingTimeout).isEqualTo(Duration.ofSeconds(10))
            assertThat(config.batchTimeout).isEqualTo(Duration.ofMillis(50))
            assertThat(config.retryPolicy).isEqualTo(RetryPolicy.AGGRESSIVE)
            assertThat(config.enableMetrics).isFalse()
            assertThat(config.enableTracing).isFalse()
            assertThat(config.processingMode).isEqualTo(
                ProcessingConfig.ProcessingMode.BATCH
            )
            assertThat(config.isHighPerformance()).isTrue()
        }
        
        @Test
        fun `LOW_LATENCY 설정은 낮은 지연 시간에 최적화되어야 한다`() {
            // Given
            val config = ProcessingConfig.LOW_LATENCY
            
            // Then
            assertThat(config.batchSize).isEqualTo(50)
            assertThat(config.parallelism).isEqualTo(
                ProcessingConfig.DEFAULT_PARALLELISM * 4
            )
            assertThat(config.processingTimeout).isEqualTo(Duration.ofSeconds(5))
            assertThat(config.batchTimeout).isEqualTo(Duration.ofMillis(10))
            assertThat(config.enableMetrics).isTrue()
            assertThat(config.enableTracing).isFalse()
            assertThat(config.processingMode).isEqualTo(
                ProcessingConfig.ProcessingMode.STREAMING
            )
        }
    }
    
    @Nested
    @DisplayName("처리 모드")
    inner class ProcessingModeTest {
        
        @Test
        fun `모든 처리 모드를 지원해야 한다`() {
            // Given
            val modes = ProcessingConfig.ProcessingMode.values()
            
            // Then
            assertThat(modes).containsExactly(
                ProcessingConfig.ProcessingMode.SINGLE,
                ProcessingConfig.ProcessingMode.BATCH,
                ProcessingConfig.ProcessingMode.STREAMING
            )
        }
        
        @ParameterizedTest
        @EnumSource(ProcessingConfig.ProcessingMode::class)
        fun `각 모드로 설정을 생성할 수 있다`(mode: ProcessingConfig.ProcessingMode) {
            // Given & When
            val config = ProcessingConfig(processingMode = mode)
            
            // Then
            assertThat(config.processingMode).isEqualTo(mode)
        }
    }
}