package com.gptgongjakso.naverwriterhelper.statemachine

import com.gptgongjakso.naverwriterhelper.model.PipelineState

/**
 * 자료 처리 + 완전 자동화 상태머신 (v1.1.0, 작업지시서 5/11).
 *
 * 각 단계의 시각·재시도·실패이유·재개단계·완료증거를 기록한다.
 * 자동 진행은 절대 발행/임시저장으로 이어지지 않으며, READY_FOR_USER 에서 멈춘다.
 *
 * v1.1.0 확장:
 *  - advanceTo(): 정의된 인접 전이만 허용하는 안전 전이(오케스트레이터 전용, 지시서 14.1-2)
 *  - retryOrPause(): 단계별 최대 재시도(기본 2회) 초과 시 자동으로 PAUSED (지시서 14.1-5)
 *  - toPersistableMap()/restoreFrom(): 프로세스 재생성 후 이어하기용 직렬화(지시서 14.1-6)
 *  - markEvidence(): 단계 성공 증거(값 변화 확인 결과)를 기록(지시서 5.2)
 *
 * 순수 로직(시각은 주입) → 단위 테스트 가능. UI/오케스트레이터가 이 상태를 구독한다.
 */
class PipelineStateMachine(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    data class StepRecord(
        val state: PipelineState,
        val enteredAt: Long,
        var retries: Int = 0,
        var failReason: String? = null,
        var completed: Boolean = false,
        var evidence: String? = null
    )

    companion object {
        /** 단계당 기본 최대 재시도 횟수(작업지시서 6.4) */
        const val MAX_RETRIES_PER_STEP = 2
    }

    var current: PipelineState = PipelineState.RECEIVED
        private set

    /** 일시정지 시 돌아갈 재개 단계 */
    var resumeState: PipelineState? = null
        private set

    /** 가장 최근 일시정지/실패 사유(사용자 안내용, 원문·계정정보 없음) */
    var pauseReason: String? = null
        private set

    /** 현재 처리 중 자료 ID */
    var dataId: String? = null

    /** 태그 입력 진행 인덱스(현재 태그) */
    var currentTagIndex: Int = 0

    /** 사진 선택 진행(현재/전체) */
    var selectedPhotoCount: Int = 0
    var totalPhotoCount: Int = 0

    private val history = ArrayList<StepRecord>()
    private val completedStates = LinkedHashSet<PipelineState>()

    init {
        history.add(StepRecord(PipelineState.RECEIVED, clock()))
    }

    /**
     * 정상 진행 순서(선형, 작업지시서 5.1). 오케스트레이터의 [advanceTo] 가
     * 이 순서의 인접 전이만 허용해 "허용되지 않은 단계 전이"를 구조적으로 차단한다.
     */
    private val forwardOrder = listOf(
        PipelineState.RECEIVED,
        PipelineState.VALIDATING,
        PipelineState.DUPLICATE_CHECKING,
        PipelineState.PARSING,
        PipelineState.STORING,
        PipelineState.CONVERTING_IMAGES,
        PipelineState.SAVING_IMAGES,
        PipelineState.READY_TO_AUTOMATE,
        PipelineState.OPENING_NAVER,
        PipelineState.WAITING_NAVER_HOME,
        PipelineState.OPENING_WRITE_SCREEN,
        PipelineState.VERIFYING_WRITE_SCREEN,
        PipelineState.OPENING_CATEGORY,
        PipelineState.SELECTING_CATEGORY,
        PipelineState.VERIFYING_CATEGORY,
        PipelineState.INPUTTING_TITLE,
        PipelineState.VERIFYING_TITLE,
        PipelineState.INPUTTING_BODY,
        PipelineState.VERIFYING_BODY,
        PipelineState.OPENING_PHOTO_PICKER,
        PipelineState.OPENING_GPT_ALBUM,
        PipelineState.SELECTING_PHOTOS,
        PipelineState.VERIFYING_PHOTO_COUNT,
        PipelineState.CONFIRMING_PHOTOS,
        PipelineState.VERIFYING_PHOTO_ATTACH,
        PipelineState.OPENING_TAG_FIELD,
        PipelineState.INPUTTING_TAGS,
        PipelineState.VERIFYING_TAGS,
        PipelineState.READY_FOR_USER
    )

    fun forwardOrderSnapshot(): List<PipelineState> = forwardOrder

    /** 다음 정상 단계로 전이(레거시 헬퍼). 실패/취소/일시정지는 별도 메서드 사용. */
    fun advance(): PipelineState {
        val idx = forwardOrder.indexOf(current)
        if (idx >= 0 && idx < forwardOrder.size - 1) {
            markCompleted(current)
            transitionTo(forwardOrder[idx + 1])
        }
        return current
    }

    /** next 가 현재 단계 바로 다음 정의된 단계인지 */
    fun isValidForwardTransition(next: PipelineState): Boolean {
        val idx = forwardOrder.indexOf(current)
        val nextIdx = forwardOrder.indexOf(next)
        return idx >= 0 && nextIdx == idx + 1
    }

    /**
     * 오케스트레이터 전용 안전 전이. [forwardOrder]의 인접 단계가 아니면 전이를 거부하고
     * false 를 반환한다(작업지시서 14.1-2: 허용되지 않은 단계 전이 차단).
     */
    fun advanceTo(next: PipelineState, evidence: String? = null): Boolean {
        if (current.isTerminal) return false
        if (!isValidForwardTransition(next)) return false
        evidence?.let { markEvidence(it) }
        markCompleted(current)
        transitionTo(next)
        return true
    }

    /** 특정 단계로 직접 전이(관리용, 검증 없이 허용 — 기존 자료 처리 파이프라인 호환). */
    fun transitionTo(next: PipelineState): PipelineState {
        if (current.isTerminal) return current // 종료 상태에서는 전이 불가
        current = next
        history.add(StepRecord(next, clock()))
        return current
    }

    fun pause(reason: String? = null) {
        if (current == PipelineState.PAUSED || current.isTerminal) return
        resumeState = current
        pauseReason = reason
        current.let { record(it)?.failReason = reason }
        transitionTo(PipelineState.PAUSED)
    }

    /** 일시정지 해제 → 재개 단계로 정확히 복귀(지시서 14.1-3). */
    fun resume(): PipelineState {
        if (current != PipelineState.PAUSED) return current
        val back = resumeState ?: return current
        resumeState = null
        pauseReason = null
        current = back
        history.add(StepRecord(back, clock()))
        return current
    }

    fun fail(reason: String): PipelineState {
        pauseReason = reason
        record(current)?.failReason = reason
        return transitionToTerminal(PipelineState.FAILED)
    }

    fun cancel(): PipelineState = transitionToTerminal(PipelineState.CANCELLED)

    fun completeByUser(): PipelineState = transitionToTerminal(PipelineState.COMPLETED_BY_USER)

    fun retry(): Int {
        val r = record(current) ?: return 0
        r.retries += 1
        return r.retries
    }

    /**
     * 재시도하고, 단계별 최대 재시도([MAX_RETRIES_PER_STEP])를 초과하면 자동으로 PAUSED 처리한다
     * (작업지시서 14.1-5: 최대 재시도 초과 후 PAUSED).
     * @return true = 재시도 계속 가능, false = 초과하여 일시정지됨
     */
    fun retryOrPause(reason: String): Boolean {
        val r = retry()
        return if (r > MAX_RETRIES_PER_STEP) {
            pause(reason)
            false
        } else {
            true
        }
    }

    fun retriesOf(state: PipelineState): Int = record(state)?.retries ?: 0

    /** 현재 단계의 성공 증거(예: "입력값 확인됨", "게시판명 일치")를 기록한다. */
    fun markEvidence(evidence: String) {
        record(current)?.evidence = evidence
    }

    private fun transitionToTerminal(state: PipelineState): PipelineState {
        current = state
        history.add(StepRecord(state, clock(), completed = true))
        return current
    }

    private fun markCompleted(state: PipelineState) {
        record(state)?.completed = true
        completedStates.add(state)
    }

    private fun record(state: PipelineState): StepRecord? =
        history.lastOrNull { it.state == state }

    fun snapshot(): List<StepRecord> = history.toList()

    fun completedStatesSnapshot(): Set<PipelineState> = completedStates.toSet()

    /** "태그 3/8" 같은 사용자 표시용 요약 */
    fun statusSummary(): String = when (current) {
        PipelineState.INPUTTING_TAGS, PipelineState.VERIFYING_TAGS ->
            "태그 입력 중 (${current.name})"
        PipelineState.SELECTING_PHOTOS, PipelineState.VERIFYING_PHOTO_COUNT ->
            "사진 선택 중 $selectedPhotoCount/$totalPhotoCount (${current.name})"
        else -> current.name
    }

    // ======================= 직렬화 (작업지시서 10.3 / 14.1-6) =======================
    // 본문·제목 전문, 계정정보는 절대 포함하지 않는다. 상태/카운터/ID만 저장한다.

    fun toPersistableMap(): Map<String, String> = mapOf(
        "current" to current.name,
        "resumeState" to (resumeState?.name ?: ""),
        "pauseReason" to (pauseReason ?: ""),
        "dataId" to (dataId ?: ""),
        "currentTagIndex" to currentTagIndex.toString(),
        "selectedPhotoCount" to selectedPhotoCount.toString(),
        "totalPhotoCount" to totalPhotoCount.toString(),
        "completedStates" to completedStates.joinToString(",") { it.name },
        "currentRetries" to retriesOf(current).toString(),
        "updatedAt" to clock().toString()
    )

    fun restoreFrom(map: Map<String, String>) {
        val c = map["current"]?.takeIf { it.isNotBlank() }
            ?.let { runCatching { PipelineState.valueOf(it) }.getOrNull() } ?: return
        current = c
        resumeState = map["resumeState"]?.takeIf { it.isNotBlank() }
            ?.let { runCatching { PipelineState.valueOf(it) }.getOrNull() }
        pauseReason = map["pauseReason"]?.takeIf { it.isNotBlank() }
        dataId = map["dataId"]?.takeIf { it.isNotBlank() }
        currentTagIndex = map["currentTagIndex"]?.toIntOrNull() ?: 0
        selectedPhotoCount = map["selectedPhotoCount"]?.toIntOrNull() ?: 0
        totalPhotoCount = map["totalPhotoCount"]?.toIntOrNull() ?: 0
        completedStates.clear()
        map["completedStates"]?.split(",")?.forEach { name ->
            if (name.isNotBlank()) {
                runCatching { PipelineState.valueOf(name) }.getOrNull()?.let { completedStates.add(it) }
            }
        }
        history.clear()
        history.add(
            StepRecord(current, clock(), retries = map["currentRetries"]?.toIntOrNull() ?: 0)
        )
    }
}
