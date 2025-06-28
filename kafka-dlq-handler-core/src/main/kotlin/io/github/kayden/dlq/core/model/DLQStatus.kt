package io.github.kayden.dlq.core.model

/**
 * DLQ 메시지의 처리 상태
 * 
 * DLQ 메시지의 생명주기를 나타내는 상태 열거형.
 * 각 상태는 메시지가 처리되는 과정에서의 특정 단계를 표현함.
 * 
 * @since 0.1.0
 */
enum class DLQStatus {
    /**
     * 대기 중 - 초기 상태
     * 
     * DLQ에 처음 들어왔거나 재처리를 대기하는 상태.
     * 이 상태의 메시지는 재처리 대상으로 선택될 수 있음.
     */
    PENDING,
    
    /**
     * 재처리 중
     * 
     * 현재 재처리가 진행 중인 상태.
     * 동시에 여러 번 재처리되는 것을 방지하기 위한 상태.
     */
    RETRYING,
    
    /**
     * 성공
     * 
     * 재처리가 성공적으로 완료된 상태.
     * 이 상태의 메시지는 더 이상 재처리되지 않음.
     */
    SUCCESS,
    
    /**
     * 실패
     * 
     * 최대 재시도 횟수를 초과하여 영구적으로 실패한 상태.
     * 수동 개입이 필요한 메시지.
     */
    FAILED,
    
    /**
     * 만료됨
     * 
     * 설정된 TTL(Time To Live)을 초과하여 만료된 상태.
     * 더 이상 재처리 대상이 아닌 메시지.
     */
    EXPIRED,
    
    /**
     * 스킵됨
     * 
     * 특정 조건에 의해 재처리가 스킵된 상태.
     * 예: 필터링 규칙, 비즈니스 로직에 의한 스킵.
     */
    SKIPPED,
    
    /**
     * 보류 중
     * 
     * 수동 검토나 추가 조치가 필요하여 보류된 상태.
     * 자동 재처리 대상에서 제외됨.
     */
    ON_HOLD;
    
    /**
     * 재처리 가능한 상태인지 확인
     */
    fun isRetryable(): Boolean = this == PENDING
    
    /**
     * 최종 상태인지 확인 (더 이상 상태 변경 불가)
     */
    fun isFinal(): Boolean = this in setOf(SUCCESS, FAILED, EXPIRED)
    
    /**
     * 활성 상태인지 확인 (처리 중인 상태)
     */
    fun isActive(): Boolean = this in setOf(PENDING, RETRYING)
}