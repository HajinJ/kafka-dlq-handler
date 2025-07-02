package io.github.kayden.dlq.core.model

/**
 * DLQ 메시지의 처리 상태를 나타내는 열거형.
 * 
 * 각 상태는 메시지가 DLQ 처리 파이프라인에서 어떤 단계에 있는지를 나타낸다.
 * 
 * @since 0.1.0
 */
enum class DLQStatus {
    /**
     * 초기 상태. 메시지가 DLQ에 추가되었지만 아직 처리되지 않은 상태.
     */
    PENDING,
    
    /**
     * 재처리 중. 메시지가 현재 재처리되고 있는 상태.
     */
    RETRYING,
    
    /**
     * 성공. 메시지가 성공적으로 재처리되어 원본 토픽으로 전송된 상태.
     */
    SUCCESS,
    
    /**
     * 실패. 재처리가 실패한 상태. 재시도 가능할 수 있음.
     */
    FAILED,
    
    /**
     * 만료. 재시도 제한 또는 시간 제한을 초과하여 더 이상 처리하지 않는 상태.
     */
    EXPIRED,
    
    /**
     * 건너뜀. 특정 조건에 의해 처리를 건너뛴 상태.
     * 예: 영구 실패 타입, 특정 에러 타입 등.
     */
    SKIPPED,
    
    /**
     * 보류. 수동 개입이 필요하거나 특정 조건이 충족될 때까지 대기하는 상태.
     */
    ON_HOLD;
    
    /**
     * 터미널 상태인지 확인한다.
     * 터미널 상태는 더 이상 상태 변경이 일어나지 않는 최종 상태를 의미한다.
     * 
     * @return 터미널 상태이면 true
     */
    fun isTerminal(): Boolean = this in terminalStates
    
    /**
     * 활성 상태인지 확인한다.
     * 활성 상태는 현재 처리 중이거나 처리 대기 중인 상태를 의미한다.
     * 
     * @return 활성 상태이면 true
     */
    fun isActive(): Boolean = this in activeStates
    
    /**
     * 재시도 가능한 상태인지 확인한다.
     * 
     * @return 재시도 가능한 상태이면 true
     */
    fun isRetryable(): Boolean = this in retryableStates
    
    companion object {
        /**
         * 터미널 상태 집합. 더 이상 상태 변경이 없는 최종 상태들.
         */
        val terminalStates = setOf(SUCCESS, EXPIRED, SKIPPED)
        
        /**
         * 활성 상태 집합. 처리 중이거나 처리 대기 중인 상태들.
         */
        val activeStates = setOf(PENDING, RETRYING, ON_HOLD)
        
        /**
         * 재시도 가능한 상태 집합.
         */
        val retryableStates = setOf(PENDING, FAILED)
        
        /**
         * 상태 전환이 유효한지 검증한다.
         * 
         * @param from 현재 상태
         * @param to 전환하려는 상태
         * @return 유효한 전환이면 true
         */
        fun isValidTransition(from: DLQStatus, to: DLQStatus): Boolean {
            return when (from) {
                PENDING -> to in setOf(RETRYING, SKIPPED, ON_HOLD, EXPIRED)
                RETRYING -> to in setOf(SUCCESS, FAILED, EXPIRED)
                FAILED -> to in setOf(RETRYING, EXPIRED, SKIPPED, ON_HOLD)
                ON_HOLD -> to in setOf(PENDING, SKIPPED, EXPIRED)
                SUCCESS, EXPIRED, SKIPPED -> false // 터미널 상태에서는 전환 불가
            }
        }
    }
}