package io.github.kayden.dlq.core.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.minutes

class ProcessingConfigTest {
    
    @Test
    @DisplayName("유효한 파라미터로 ProcessingConfig를 생성할 수 있다")
    fun shouldCreateProcessingConfigWithValidParameters() {
        val config = ProcessingConfig(
            batchSize = 1000,
            parallelism = 8,
            processingTimeout = 60.seconds,
            batchTimeout = 200.milliseconds,
            bufferSize = 50_000,
            backpressureThreshold = 0.9
        )
        
        assertEquals(1000, config.batchSize)
        assertEquals(8, config.parallelism)
        assertEquals(60.seconds, config.processingTimeout)
        assertEquals(200.milliseconds, config.batchTimeout)
        assertEquals(50_000, config.bufferSize)
        assertEquals(0.9, config.backpressureThreshold)
    }
    
    @Test
    @DisplayName("기본값으로 ProcessingConfig를 생성할 수 있다")
    fun shouldCreateWithDefaults() {
        val config = ProcessingConfig()
        
        assertEquals(100, config.batchSize)
        assertEquals(Runtime.getRuntime().availableProcessors(), config.parallelism)
        assertEquals(30.seconds, config.processingTimeout)
        assertEquals(100.milliseconds, config.batchTimeout)
        assertEquals(RetryPolicy.DEFAULT, config.retryPolicy)
        assertTrue(config.enableMetrics)
        assertFalse(config.enableTracing)
        assertEquals(10_000, config.bufferSize)
        assertEquals(0.8, config.backpressureThreshold)
    }
    
    @Test
    @DisplayName("유효하지 않은 파라미터로 생성할 수 없다")
    fun shouldValidateParameters() {
        // 배치 크기
        assertThrows<IllegalArgumentException> {
            ProcessingConfig(batchSize = 0)
        }
        assertThrows<IllegalArgumentException> {
            ProcessingConfig(batchSize = -1)
        }
        
        // 병렬 처리 수준
        assertThrows<IllegalArgumentException> {
            ProcessingConfig(parallelism = 0)
        }
        assertThrows<IllegalArgumentException> {
            ProcessingConfig(parallelism = -1)
        }
        
        // 타임아웃
        assertThrows<IllegalArgumentException> {
            ProcessingConfig(processingTimeout = 0.seconds)
        }
        assertThrows<IllegalArgumentException> {
            ProcessingConfig(batchTimeout = (-1).seconds)
        }
        
        // 버퍼 크기
        assertThrows<IllegalArgumentException> {
            ProcessingConfig(bufferSize = 0)
        }
        
        // 백프레셔 임계값
        assertThrows<IllegalArgumentException> {
            ProcessingConfig(backpressureThreshold = -0.1)
        }
        assertThrows<IllegalArgumentException> {
            ProcessingConfig(backpressureThreshold = 1.1)
        }
        
        // 버퍼 크기는 배치 크기보다 커야 함
        assertThrows<IllegalArgumentException> {
            ProcessingConfig(batchSize = 1000, bufferSize = 500)
        }
    }
    
    @Test
    @DisplayName("백프레셔 활성화 크기를 올바르게 계산한다")
    fun shouldCalculateBackpressureActivationSize() {
        val config = ProcessingConfig(
            bufferSize = 10_000,
            backpressureThreshold = 0.8
        )
        
        assertEquals(8000, config.backpressureActivationSize())
        
        val config2 = ProcessingConfig(
            bufferSize = 1000,
            backpressureThreshold = 0.5
        )
        
        assertEquals(500, config2.backpressureActivationSize())
    }
    
    @Test
    @DisplayName("필요한 배치 수를 올바르게 계산한다")
    fun shouldCalculateBatchCount() {
        val config = ProcessingConfig(batchSize = 100)
        
        assertEquals(1, config.calculateBatchCount(1))
        assertEquals(1, config.calculateBatchCount(100))
        assertEquals(2, config.calculateBatchCount(101))
        assertEquals(10, config.calculateBatchCount(1000))
        assertEquals(11, config.calculateBatchCount(1001))
    }
    
    @Test
    @DisplayName("예상 처리 시간을 올바르게 계산한다")
    fun shouldEstimateProcessingTime() {
        val config = ProcessingConfig(
            batchSize = 100,
            parallelism = 4
        )
        
        // 400 메시지, 배치당 1초 = 4 배치
        // 4 병렬 처리 = 1 라운드
        val estimate1 = config.estimateProcessingTime(400, 1.seconds)
        assertEquals(1.seconds, estimate1)
        
        // 1000 메시지 = 10 배치
        // 4 병렬 처리 = 3 라운드 (4 + 4 + 2)
        val estimate2 = config.estimateProcessingTime(1000, 1.seconds)
        assertEquals(3.seconds, estimate2)
    }
    
    @Test
    @DisplayName("처리량 기반 최적화가 올바르게 동작한다")
    fun shouldOptimizeForThroughput() {
        val baseConfig = ProcessingConfig()
        
        // 낮은 처리량
        val lowThroughput = baseConfig.optimizeForThroughput(500)
        assertEquals(50, lowThroughput.batchSize)
        assertTrue(lowThroughput.parallelism >= 1)
        
        // 중간 처리량
        val midThroughput = baseConfig.optimizeForThroughput(5_000)
        assertEquals(200, midThroughput.batchSize)
        
        // 높은 처리량
        val highThroughput = baseConfig.optimizeForThroughput(50_000)
        assertEquals(1000, highThroughput.batchSize)
        assertEquals(Runtime.getRuntime().availableProcessors(), highThroughput.parallelism)
        
        // 매우 높은 처리량
        val veryHighThroughput = baseConfig.optimizeForThroughput(150_000)
        assertEquals(5000, veryHighThroughput.batchSize)
        assertEquals(Runtime.getRuntime().availableProcessors() * 2, veryHighThroughput.parallelism)
    }
    
    @Test
    @DisplayName("지연시간 기반 최적화가 올바르게 동작한다")
    fun shouldOptimizeForLatency() {
        val baseConfig = ProcessingConfig()
        
        // 매우 낮은 지연시간
        val veryLowLatency = baseConfig.optimizeForLatency(5.milliseconds)
        assertEquals(1, veryLowLatency.batchSize)
        assertEquals(500.microseconds, veryLowLatency.batchTimeout)
        
        // 낮은 지연시간
        val lowLatency = baseConfig.optimizeForLatency(50.milliseconds)
        assertEquals(10, lowLatency.batchSize)
        assertEquals(5.milliseconds, lowLatency.batchTimeout)
        
        // 중간 지연시간
        val midLatency = baseConfig.optimizeForLatency(500.milliseconds)
        assertEquals(50, midLatency.batchSize)
        
        // 높은 지연시간
        val highLatency = baseConfig.optimizeForLatency(2.seconds)
        assertEquals(100, highLatency.batchSize)
    }
    
    @Test
    @DisplayName("사전 정의된 설정들이 올바른 특성을 가진다")
    fun shouldHaveCorrectPredefinedConfigs() {
        // HIGH_THROUGHPUT
        val highThroughput = ProcessingConfig.HIGH_THROUGHPUT
        assertEquals(5000, highThroughput.batchSize)
        assertEquals(Runtime.getRuntime().availableProcessors() * 2, highThroughput.parallelism)
        assertEquals(100_000, highThroughput.bufferSize)
        assertEquals(0.9, highThroughput.backpressureThreshold)
        
        // LOW_LATENCY
        val lowLatency = ProcessingConfig.LOW_LATENCY
        assertEquals(10, lowLatency.batchSize)
        assertEquals(10.milliseconds, lowLatency.batchTimeout)
        assertEquals(1000, lowLatency.bufferSize)
        
        // MEMORY_EFFICIENT
        val memEfficient = ProcessingConfig.MEMORY_EFFICIENT
        assertEquals(50, memEfficient.batchSize)
        assertEquals(1000, memEfficient.bufferSize)
        assertEquals(0.6, memEfficient.backpressureThreshold)
        
        // DEVELOPMENT
        val development = ProcessingConfig.DEVELOPMENT
        assertEquals(1, development.batchSize)
        assertEquals(1, development.parallelism)
        assertTrue(development.enableMetrics)
        assertTrue(development.enableTracing)
    }
    
    @Test
    @DisplayName("자동 설정이 시스템 리소스를 고려한다")
    fun shouldAutoConfigureBasedOnResources() {
        // 낮은 메모리
        val lowMemConfig = ProcessingConfig.autoConfig(
            availableMemoryMb = 512,
            targetThroughput = null
        )
        assertEquals(0.7, lowMemConfig.backpressureThreshold)
        assertTrue(lowMemConfig.bufferSize <= 52428) // ~10% of 512MB
        
        // 높은 메모리
        val highMemConfig = ProcessingConfig.autoConfig(
            availableMemoryMb = 8192,
            targetThroughput = null
        )
        assertEquals(0.8, highMemConfig.backpressureThreshold)
        
        // 목표 처리량 지정
        val withThroughput = ProcessingConfig.autoConfig(
            availableMemoryMb = 2048,
            targetThroughput = 50_000
        )
        assertEquals(1000, withThroughput.batchSize)
    }
    
    @Test
    @DisplayName("ProcessingConfigBuilder가 올바르게 동작한다")
    fun shouldBuildConfigWithBuilder() {
        val config = processingConfig {
            batchSize(500)
            parallelism(16)
            processingTimeout(45.seconds)
            batchTimeout(150.milliseconds)
            enableMetrics()
            enableTracing(false)
            bufferSize(20_000)
            backpressureThreshold(0.75)
        }
        
        assertEquals(500, config.batchSize)
        assertEquals(16, config.parallelism)
        assertEquals(45.seconds, config.processingTimeout)
        assertEquals(150.milliseconds, config.batchTimeout)
        assertTrue(config.enableMetrics)
        assertFalse(config.enableTracing)
        assertEquals(20_000, config.bufferSize)
        assertEquals(0.75, config.backpressureThreshold)
    }
    
    @Test
    @DisplayName("빌더로 재시도 정책을 설정할 수 있다")
    fun shouldSetRetryPolicyWithBuilder() {
        val customRetryPolicy = RetryPolicy(maxRetries = 5)
        
        val config = processingConfig {
            retryPolicy(customRetryPolicy)
        }
        
        assertEquals(customRetryPolicy, config.retryPolicy)
        assertEquals(5, config.retryPolicy.maxRetries)
    }
    
    @Test
    @DisplayName("설정 조합이 일관성을 유지한다")
    fun shouldMaintainConfigurationConsistency() {
        // 배치 크기와 버퍼 크기의 관계
        val config1 = ProcessingConfig(batchSize = 1000, bufferSize = 1000)
        assertEquals(config1.batchSize, config1.bufferSize)
        
        // 백프레셔 임계값과 실제 크기
        val config2 = ProcessingConfig(
            bufferSize = 1000,
            backpressureThreshold = 0.8
        )
        assertEquals(800, config2.backpressureActivationSize())
        assertTrue(config2.backpressureActivationSize() < config2.bufferSize)
        
        // 병렬 처리와 배치 수
        val config3 = ProcessingConfig(
            batchSize = 100,
            parallelism = 4
        )
        val batchCount = config3.calculateBatchCount(1000) // 10 배치
        val rounds = (batchCount + config3.parallelism - 1) / config3.parallelism
        assertEquals(3, rounds) // 4 + 4 + 2
    }
}