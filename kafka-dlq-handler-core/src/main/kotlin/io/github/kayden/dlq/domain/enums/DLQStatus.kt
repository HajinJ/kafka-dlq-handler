package io.github.kayden.dlq.domain.enums

/**
 * DLQ 메시지의 처리 상태를 나타내는 열거형
 */
enum class DLQStatus {
    /**
     * 처리 대기 중
     * - 초기 상태 또는 재처리 대기 중인 메시지
     */
    PENDING,
    
    /**
     * 재처리 진행 중
     * - 현재 재처리가 시도되고 있는 상태
     */
    PROCESSING,
    
    /**
     * 재처리 성공
     * - 메시지가 성공적으로 처리됨
     */
    SUCCESS,
    
    /**
     * 최종 실패
     * - 재시도 한계에 도달하여 더 이상 재처리되지 않음
     */
    FAILED,
    
    /**
     * 만료됨
     * - 보존 기간이 지나 만료된 메시지
     */
    EXPIRED,
    
    /**
     * 무시됨
     * - 운영자가 수동으로 무시 처리한 메시지
     */
    IGNORED,
    
    /**
     * 수동 검토 필요
     * - 자동 처리가 불가능하여 수동 개입이 필요한 메시지
     */
    PARKED
}