package com.gptgongjakso.naverwriterhelper.automation

import com.gptgongjakso.naverwriterhelper.model.PipelineState

/**
 * 단계별 기본 타임아웃 (작업지시서 6.4). v1.1.0 신규.
 * 값은 지시서에 명시된 기준을 그대로 사용한다. 실기 데이터가 쌓이면 조정한다.
 */
object AutomationTimeouts {
    const val OPEN_NAVER_MS = 15_000L
    const val FIND_WRITE_SCREEN_MS = 15_000L
    const val OPEN_CATEGORY_MS = 10_000L
    const val SELECT_CATEGORY_MS = 15_000L
    const val INPUT_TITLE_MS = 10_000L
    const val INPUT_BODY_MS = 25_000L
    const val OPEN_PHOTO_PICKER_MS = 15_000L
    const val OPEN_ALBUM_MS = 15_000L
    const val SELECT_PHOTO_EACH_MS = 5_000L
    const val CONFIRM_PHOTOS_MS = 15_000L
    const val INPUT_TAG_EACH_MS = 5_000L
    const val VERIFY_FINAL_MS = 10_000L

    /** 단계별 타임아웃 조회. 정의되지 않은 단계는 넉넉한 기본값(15초)을 사용한다. */
    fun forState(state: PipelineState): Long = when (state) {
        PipelineState.OPENING_NAVER -> OPEN_NAVER_MS
        PipelineState.WAITING_NAVER_HOME,
        PipelineState.OPENING_WRITE_SCREEN,
        PipelineState.VERIFYING_WRITE_SCREEN -> FIND_WRITE_SCREEN_MS
        PipelineState.OPENING_CATEGORY -> OPEN_CATEGORY_MS
        PipelineState.SELECTING_CATEGORY,
        PipelineState.VERIFYING_CATEGORY -> SELECT_CATEGORY_MS
        PipelineState.INPUTTING_TITLE,
        PipelineState.VERIFYING_TITLE -> INPUT_TITLE_MS
        PipelineState.INPUTTING_BODY,
        PipelineState.VERIFYING_BODY -> INPUT_BODY_MS
        PipelineState.OPENING_PHOTO_PICKER -> OPEN_PHOTO_PICKER_MS
        PipelineState.OPENING_GPT_ALBUM -> OPEN_ALBUM_MS
        PipelineState.SELECTING_PHOTOS,
        PipelineState.VERIFYING_PHOTO_COUNT -> SELECT_PHOTO_EACH_MS
        PipelineState.CONFIRMING_PHOTOS,
        PipelineState.VERIFYING_PHOTO_ATTACH -> CONFIRM_PHOTOS_MS
        PipelineState.OPENING_TAG_FIELD,
        PipelineState.INPUTTING_TAGS,
        PipelineState.VERIFYING_TAGS -> INPUT_TAG_EACH_MS
        else -> 15_000L
    }
}
