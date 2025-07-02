package io.github.kayden.dlq.core.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.CsvSource

class DLQStatusTest {
    
    @Test
    @DisplayName("터미널 상태를 올바르게 식별한다")
    fun shouldIdentifyTerminalStates() {
        assertTrue(DLQStatus.SUCCESS.isTerminal())
        assertTrue(DLQStatus.EXPIRED.isTerminal())
        assertTrue(DLQStatus.SKIPPED.isTerminal())
        
        assertFalse(DLQStatus.PENDING.isTerminal())
        assertFalse(DLQStatus.RETRYING.isTerminal())
        assertFalse(DLQStatus.FAILED.isTerminal())
        assertFalse(DLQStatus.ON_HOLD.isTerminal())
    }
    
    @Test
    @DisplayName("활성 상태를 올바르게 식별한다")
    fun shouldIdentifyActiveStates() {
        assertTrue(DLQStatus.PENDING.isActive())
        assertTrue(DLQStatus.RETRYING.isActive())
        assertTrue(DLQStatus.ON_HOLD.isActive())
        
        assertFalse(DLQStatus.SUCCESS.isActive())
        assertFalse(DLQStatus.FAILED.isActive())
        assertFalse(DLQStatus.EXPIRED.isActive())
        assertFalse(DLQStatus.SKIPPED.isActive())
    }
    
    @Test
    @DisplayName("재시도 가능한 상태를 올바르게 식별한다")
    fun shouldIdentifyRetryableStates() {
        assertTrue(DLQStatus.PENDING.isRetryable())
        assertTrue(DLQStatus.FAILED.isRetryable())
        
        assertFalse(DLQStatus.RETRYING.isRetryable())
        assertFalse(DLQStatus.SUCCESS.isRetryable())
        assertFalse(DLQStatus.EXPIRED.isRetryable())
        assertFalse(DLQStatus.SKIPPED.isRetryable())
        assertFalse(DLQStatus.ON_HOLD.isRetryable())
    }
    
    @ParameterizedTest
    @CsvSource(
        "PENDING, RETRYING, true",
        "PENDING, SKIPPED, true",
        "PENDING, ON_HOLD, true",
        "PENDING, EXPIRED, true",
        "PENDING, SUCCESS, false",
        "PENDING, FAILED, false",
        "RETRYING, SUCCESS, true",
        "RETRYING, FAILED, true",
        "RETRYING, EXPIRED, true",
        "RETRYING, PENDING, false",
        "RETRYING, SKIPPED, false",
        "FAILED, RETRYING, true",
        "FAILED, EXPIRED, true",
        "FAILED, SKIPPED, true",
        "FAILED, ON_HOLD, true",
        "FAILED, SUCCESS, false",
        "ON_HOLD, PENDING, true",
        "ON_HOLD, SKIPPED, true",
        "ON_HOLD, EXPIRED, true",
        "ON_HOLD, RETRYING, false",
        "SUCCESS, PENDING, false",
        "SUCCESS, RETRYING, false",
        "EXPIRED, PENDING, false",
        "SKIPPED, RETRYING, false"
    )
    @DisplayName("상태 전환 유효성을 올바르게 검증한다")
    fun shouldValidateStateTransitions(from: DLQStatus, to: DLQStatus, expected: Boolean) {
        assertEquals(
            expected,
            DLQStatus.isValidTransition(from, to),
            "Transition from $from to $to should be ${if (expected) "valid" else "invalid"}"
        )
    }
    
    @Test
    @DisplayName("터미널 상태에서는 어떤 상태로도 전환할 수 없다")
    fun shouldNotAllowTransitionFromTerminalStates() {
        val terminalStates = listOf(DLQStatus.SUCCESS, DLQStatus.EXPIRED, DLQStatus.SKIPPED)
        val allStates = DLQStatus.values().toList()
        
        for (terminalState in terminalStates) {
            for (targetState in allStates) {
                assertFalse(
                    DLQStatus.isValidTransition(terminalState, targetState),
                    "Should not allow transition from terminal state $terminalState to $targetState"
                )
            }
        }
    }
    
    @Test
    @DisplayName("companion object 상태 집합이 올바르게 정의되어 있다")
    fun shouldHaveCorrectStateCollections() {
        assertEquals(
            setOf(DLQStatus.SUCCESS, DLQStatus.EXPIRED, DLQStatus.SKIPPED),
            DLQStatus.terminalStates
        )
        
        assertEquals(
            setOf(DLQStatus.PENDING, DLQStatus.RETRYING, DLQStatus.ON_HOLD),
            DLQStatus.activeStates
        )
        
        assertEquals(
            setOf(DLQStatus.PENDING, DLQStatus.FAILED),
            DLQStatus.retryableStates
        )
    }
    
    @ParameterizedTest
    @EnumSource(DLQStatus::class)
    @DisplayName("모든 상태는 정확히 하나의 카테고리에 속한다")
    fun shouldBelongToExactlyOneCategory(status: DLQStatus) {
        val categories = listOf(
            status.isTerminal(),
            status.isActive(),
            status == DLQStatus.FAILED
        )
        
        assertEquals(
            1,
            categories.count { it },
            "$status should belong to exactly one category"
        )
    }
    
    @Test
    @DisplayName("PENDING 상태의 유효한 전환 경로")
    fun shouldAllowValidTransitionsFromPending() {
        assertTrue(DLQStatus.isValidTransition(DLQStatus.PENDING, DLQStatus.RETRYING))
        assertTrue(DLQStatus.isValidTransition(DLQStatus.PENDING, DLQStatus.SKIPPED))
        assertTrue(DLQStatus.isValidTransition(DLQStatus.PENDING, DLQStatus.ON_HOLD))
        assertTrue(DLQStatus.isValidTransition(DLQStatus.PENDING, DLQStatus.EXPIRED))
    }
    
    @Test
    @DisplayName("RETRYING 상태의 유효한 전환 경로")
    fun shouldAllowValidTransitionsFromRetrying() {
        assertTrue(DLQStatus.isValidTransition(DLQStatus.RETRYING, DLQStatus.SUCCESS))
        assertTrue(DLQStatus.isValidTransition(DLQStatus.RETRYING, DLQStatus.FAILED))
        assertTrue(DLQStatus.isValidTransition(DLQStatus.RETRYING, DLQStatus.EXPIRED))
    }
    
    @Test
    @DisplayName("FAILED 상태의 유효한 전환 경로")
    fun shouldAllowValidTransitionsFromFailed() {
        assertTrue(DLQStatus.isValidTransition(DLQStatus.FAILED, DLQStatus.RETRYING))
        assertTrue(DLQStatus.isValidTransition(DLQStatus.FAILED, DLQStatus.EXPIRED))
        assertTrue(DLQStatus.isValidTransition(DLQStatus.FAILED, DLQStatus.SKIPPED))
        assertTrue(DLQStatus.isValidTransition(DLQStatus.FAILED, DLQStatus.ON_HOLD))
    }
}