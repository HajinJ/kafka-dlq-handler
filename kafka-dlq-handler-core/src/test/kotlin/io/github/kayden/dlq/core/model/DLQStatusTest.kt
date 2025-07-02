package io.github.kayden.dlq.core.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@DisplayName("DLQStatus 테스트")
class DLQStatusTest {
    
    @Test
    fun `PENDING 상태만 재처리 가능`() {
        assertThat(DLQStatus.PENDING.isRetryable()).isTrue()
        
        val nonRetryableStatuses = DLQStatus.values().filterNot { it == DLQStatus.PENDING }
        nonRetryableStatuses.forEach { status ->
            assertThat(status.isRetryable()).isFalse()
        }
    }
    
    @ParameterizedTest
    @EnumSource(value = DLQStatus::class, names = ["SUCCESS", "FAILED", "EXPIRED"])
    fun `SUCCESS, FAILED, EXPIRED는 최종 상태`(status: DLQStatus) {
        assertThat(status.isFinal()).isTrue()
    }
    
    @ParameterizedTest
    @EnumSource(value = DLQStatus::class, names = ["PENDING", "RETRYING", "SKIPPED", "ON_HOLD"])
    fun `PENDING, RETRYING, SKIPPED, ON_HOLD는 최종 상태가 아님`(status: DLQStatus) {
        assertThat(status.isFinal()).isFalse()
    }
    
    @ParameterizedTest
    @EnumSource(value = DLQStatus::class, names = ["PENDING", "RETRYING"])
    fun `PENDING과 RETRYING은 활성 상태`(status: DLQStatus) {
        assertThat(status.isActive()).isTrue()
    }
    
    @ParameterizedTest
    @EnumSource(value = DLQStatus::class, names = ["SUCCESS", "FAILED", "EXPIRED", "SKIPPED", "ON_HOLD"])
    fun `SUCCESS, FAILED, EXPIRED, SKIPPED, ON_HOLD는 비활성 상태`(status: DLQStatus) {
        assertThat(status.isActive()).isFalse()
    }
    
    @Test
    fun `모든 상태가 정의되어 있음을 확인`() {
        val expectedStatuses = setOf(
            "PENDING", "RETRYING", "SUCCESS", "FAILED", 
            "EXPIRED", "SKIPPED", "ON_HOLD"
        )
        val actualStatuses = DLQStatus.values().map { it.name }.toSet()
        
        assertThat(actualStatuses).isEqualTo(expectedStatuses)
    }
}