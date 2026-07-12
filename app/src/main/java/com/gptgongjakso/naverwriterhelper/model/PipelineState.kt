package com.gptgongjakso.naverwriterhelper.model

/**
 * 자료 처리 + 완전 자동화 상태머신 (v1.1.0, 작업지시서 5.1).
 *
 * v1.0.0의 자료 처리 단계(RECEIVED~SAVING_IMAGES)를 보존하고,
 * v1.1.0에서 네이버 자동 입력 세부 단계(READY_TO_AUTOMATE~READY_FOR_USER)를 추가한다.
 *
 * 각 단계의 시각·재시도·실패이유·재개단계·완료증거는
 * [com.gptgongjakso.naverwriterhelper.statemachine.PipelineStateMachine] 이 관리한다.
 */
enum class PipelineState {
    // ---- 자료 처리 단계 (v1.0.0 유지) ----
    RECEIVED,
    VALIDATING,
    DUPLICATE_CHECKING,
    PARSING,
    STORING,
    CONVERTING_IMAGES,
    SAVING_IMAGES,

    // ---- 자동화 준비/진입 (v1.1.0 신규) ----
    READY_TO_AUTOMATE,
    OPENING_NAVER,
    WAITING_NAVER_HOME,
    OPENING_WRITE_SCREEN,
    VERIFYING_WRITE_SCREEN,

    // ---- 게시판 ----
    OPENING_CATEGORY,
    SELECTING_CATEGORY,
    VERIFYING_CATEGORY,

    // ---- 제목/본문 ----
    INPUTTING_TITLE,
    VERIFYING_TITLE,
    INPUTTING_BODY,
    VERIFYING_BODY,

    // ---- 사진 ----
    OPENING_PHOTO_PICKER,
    OPENING_GPT_ALBUM,
    SELECTING_PHOTOS,
    VERIFYING_PHOTO_COUNT,
    CONFIRMING_PHOTOS,
    VERIFYING_PHOTO_ATTACH,

    // ---- 태그 ----
    OPENING_TAG_FIELD,
    INPUTTING_TAGS,
    VERIFYING_TAGS,

    // ---- 종료류 ----
    READY_FOR_USER,
    PAUSED,
    FAILED,
    CANCELLED,
    COMPLETED_BY_USER;

    /** 종료 상태(더 이상 진행하지 않음) 여부 */
    val isTerminal: Boolean
        get() = this == FAILED || this == CANCELLED || this == COMPLETED_BY_USER

    /** 사용자 확인 대기/자동 진행을 멈추는 상태 여부 */
    val isHalting: Boolean
        get() = this == READY_FOR_USER || this == PAUSED || isTerminal

    /** 실제 네이버 화면 자동 조작이 이뤄지는 단계인지(자료 처리 단계 제외) */
    val isAutomationStep: Boolean
        get() = ordinal >= READY_TO_AUTOMATE.ordinal && ordinal <= VERIFYING_TAGS.ordinal
}
