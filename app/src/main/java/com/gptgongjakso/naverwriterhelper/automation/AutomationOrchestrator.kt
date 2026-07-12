package com.gptgongjakso.naverwriterhelper.automation

import com.gptgongjakso.naverwriterhelper.board.BoardMatcher
import com.gptgongjakso.naverwriterhelper.model.BoardProfile
import com.gptgongjakso.naverwriterhelper.model.PipelineState
import com.gptgongjakso.naverwriterhelper.statemachine.PipelineStateMachine

/**
 * 단일 자동 실행 책임을 가진 오케스트레이터 (작업지시서 5). v1.1.0 신규.
 *
 * 접근성 서비스는 "화면 탐색 + 1개 동작"의 결과([ActionResult])만 돌려주고,
 * 전체 순서(다음 단계 결정, 재시도, 타임아웃, 일시정지/이어하기/즉시중지)는 이 클래스가 관리한다.
 *
 * 실제 화면 조작은 [AutomationStepExecutor] 를 통해서만 수행한다 — 이 인터페이스에 가짜
 * 구현을 주입하면 Context/AccessibilityService 없이 순서·재시도·타임아웃·일시정지 로직을
 * 순수 JVM 단위 테스트로 검증할 수 있다. 마찬가지로 [scheduleNext] 를 동기 실행 람다로
 * 바꾸면 Handler/Looper 없이도 전체 흐름을 테스트할 수 있다.
 *
 * 절대 원칙: READY_FOR_USER 이후에는 어떤 클릭도 수행하지 않는다. 발행/임시저장은
 * 이 오케스트레이터의 책임 범위 밖이며, 사용자가 항상 직접 처리한다.
 */
class AutomationOrchestrator(
    private val stateMachine: PipelineStateMachine,
    private val executor: AutomationStepExecutor,
    private val boardProfile: () -> BoardProfile?,
    private val naverCategory: () -> String?,
    private val expectedPhotoCount: () -> Int,
    private val scheduleNext: (delayMs: Long, action: () -> Unit) -> Unit,
    private val onUpdate: () -> Unit = {},
    private val onLog: (String) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timeoutMsFor: (PipelineState) -> Long = { AutomationTimeouts.forState(it) }
) {
    companion object {
        /** 각 단계 사이에 화면이 안정될 시간을 주는 최소 간격 */
        const val STEP_GAP_MS = 400L
    }

    var running = false
        private set

    private var lastCategoryTarget: String? = null

    /** 게시판 자동 매칭 실패 후 사용자의 직접 선택을 기다리는 중인지(작업지시서 7.2) */
    private var categoryAwaitingManualPick = false

    /** 현재 단계에 진입한 시각(타임아웃 판정 기준, 지시서 6.4). */
    private var stateEnteredAt: Long = clock()

    /** 오케스트레이터가 정의하는 순서(상태머신의 forwardOrder 를 그대로 사용). */
    private fun nextOf(state: PipelineState): PipelineState {
        val order = stateMachine.forwardOrderSnapshot()
        val idx = order.indexOf(state)
        return if (idx in 0 until order.size - 1) order[idx + 1] else PipelineState.READY_FOR_USER
    }

    /** 전체 자동 실행 시작. READY_TO_AUTOMATE 에서만 최초 시작 가능(단일 세션 원칙은 호출측에서 잠금). */
    fun start() {
        if (running) return
        if (stateMachine.current == PipelineState.READY_TO_AUTOMATE) {
            stateMachine.advanceTo(PipelineState.OPENING_NAVER)
        }
        stateEnteredAt = clock()
        running = true
        onUpdate()
        tick()
    }

    /** 사용자 요청 일시정지. */
    fun pauseNow(reason: String = "사용자 일시정지") {
        if (!running) return
        running = false
        stateMachine.pause(reason)
        onLog("일시정지 · $reason")
        onUpdate()
    }

    /** 즉시 중지(작업지시서 10.4): 예약 작업을 모두 취소하고 CANCELLED 로 전이. */
    fun cancelNow() {
        running = false
        stateMachine.cancel()
        onLog("즉시 중지됨")
        onUpdate()
    }

    /** 이어하기: PAUSED 에서 정확히 원래 단계로 복귀 후 재개. */
    fun resumeNow() {
        if (stateMachine.current != PipelineState.PAUSED) return
        stateMachine.resume()
        stateEnteredAt = clock()
        running = true
        onLog("이어하기 · ${stateMachine.current.name}")
        onUpdate()
        scheduleNext(STEP_GAP_MS) { tick() }
    }

    /** 외부(접근성 이벤트 등)에서 화면 변화가 감지되었을 때 즉시 재확인을 트리거하고 싶을 때 호출. */
    fun kick() {
        if (running) scheduleNext(0L) { tick() }
    }

    private fun tick() {
        if (!running) return
        val state = stateMachine.current
        if (state.isHalting) {
            running = false
            onUpdate()
            return
        }
        val result = runStep(state)
        applyResult(state, result)
    }

    private fun runStep(state: PipelineState): ActionResult = when (state) {
        PipelineState.OPENING_NAVER -> executor.openNaverBlogApp()
        PipelineState.WAITING_NAVER_HOME, PipelineState.OPENING_WRITE_SCREEN -> executor.openWriteScreen()
        PipelineState.VERIFYING_WRITE_SCREEN -> executor.verifyWriteScreen()

        PipelineState.OPENING_CATEGORY -> executor.openCategory()
        PipelineState.SELECTING_CATEGORY -> {
            if (categoryAwaitingManualPick) {
                // 자동 매칭 실패 후 사용자가 직접 선택하기를 기다리는 중(작업지시서 7.2):
                // 현재 화면에 표시된 게시판명을 그대로 읽어 "사용자가 선택한 값"으로 기록한다.
                val shown = executor.readDisplayedCategory()
                if (shown.isNullOrBlank()) {
                    ActionResult.NeedsUser("게시판을 직접 선택하면 이어서 진행합니다.")
                } else {
                    lastCategoryTarget = shown
                    categoryAwaitingManualPick = false
                    com.gptgongjakso.naverwriterhelper.store.SessionRepository.manualCategorySelection = shown
                    ActionResult.Success("사용자가 선택한 게시판 확인됨")
                }
            } else {
                val target = lastCategoryTarget
                    ?: BoardMatcher.matchAgainstDisplayedTexts(naverCategory(), executor.collectCategoryTexts(), boardProfile())
                if (target == null) {
                    categoryAwaitingManualPick = true
                    ActionResult.NeedsUser(
                        "네이버에서 같은 게시판을 찾지 못했습니다. 게시판을 직접 선택하면 이어서 진행합니다."
                    )
                } else {
                    lastCategoryTarget = target
                    executor.selectCategory(target)
                }
            }
        }
        PipelineState.VERIFYING_CATEGORY ->
            executor.verifyCategorySelected(lastCategoryTarget ?: naverCategory().orEmpty())

        PipelineState.INPUTTING_TITLE -> executor.inputTitle()
        PipelineState.VERIFYING_TITLE -> executor.verifyTitle()
        PipelineState.INPUTTING_BODY -> executor.inputBody()
        PipelineState.VERIFYING_BODY -> executor.verifyBody()

        PipelineState.OPENING_PHOTO_PICKER -> executor.openPhotoButton()
        PipelineState.OPENING_GPT_ALBUM -> runPhotoAlbumEntry()
        PipelineState.SELECTING_PHOTOS -> {
            val expected = expectedPhotoCount()
            stateMachine.totalPhotoCount = expected
            if (stateMachine.selectedPhotoCount >= expected) {
                ActionResult.Success("사진 선택 완료($expected/$expected)")
            } else {
                executor.selectNextPhoto(expected, stateMachine.selectedPhotoCount)
            }
        }
        PipelineState.VERIFYING_PHOTO_COUNT -> {
            val expected = expectedPhotoCount()
            when (val actual = executor.readSelectedPhotoCount()) {
                null -> ActionResult.Retryable("선택 개수를 아직 확인하지 못함")
                expected -> ActionResult.Success("선택 개수 확인됨($actual/$expected)")
                else -> ActionResult.NeedsUser("선택된 사진 수($actual)가 기대 값($expected)과 다릅니다. 직접 확인해 주세요.")
            }
        }
        PipelineState.CONFIRMING_PHOTOS -> executor.confirmPhotoSelection()
        PipelineState.VERIFYING_PHOTO_ATTACH -> executor.verifyPhotoAttached()

        PipelineState.OPENING_TAG_FIELD -> executor.openTagField()
        PipelineState.INPUTTING_TAGS -> executor.inputSingleTag()
        PipelineState.VERIFYING_TAGS ->
            if (!com.gptgongjakso.naverwriterhelper.store.SessionRepository.tagController.hasNext())
                ActionResult.Success("태그 전체 입력 확인됨")
            else
                ActionResult.Retryable("태그가 아직 남아있음")

        else -> ActionResult.Blocked("정의되지 않은 자동화 단계: ${state.name}")
    }

    private fun runPhotoAlbumEntry(): ActionResult {
        val id = executor.identifyPhotoPicker()
        if (id !is ActionResult.Success) return id
        val album = executor.openAlbumMenu()
        if (album is ActionResult.Blocked) return album
        return executor.selectGptAlbum()
    }

    private fun applyResult(state: PipelineState, result: ActionResult) {
        when (result) {
            is ActionResult.Success -> handleSuccess(state, result)
            is ActionResult.Retryable -> handleRetryable(state, result)
            is ActionResult.NeedsUser -> {
                stateMachine.pause(result.reason)
                running = false
                onLog("일시정지 · ${result.reason}")
                onUpdate()
            }
            is ActionResult.Blocked -> {
                stateMachine.pause("안전 차단: ${result.reason}")
                running = false
                onLog("차단됨 · ${result.reason}")
                onUpdate()
            }
        }
    }

    private fun handleSuccess(state: PipelineState, result: ActionResult.Success) {
        onLog("[${state.name}] ${result.evidence}")

        if (state == PipelineState.SELECTING_PHOTOS) {
            stateMachine.selectedPhotoCount = (stateMachine.selectedPhotoCount + 1).coerceAtMost(stateMachine.totalPhotoCount)
            if (stateMachine.selectedPhotoCount < stateMachine.totalPhotoCount) {
                stateEnteredAt = clock() // 사진 1장당 타임아웃 기준 재설정(지시서 6.4)
                onUpdate()
                scheduleNext(STEP_GAP_MS) { tick() }
                return
            }
        }

        if (state == PipelineState.INPUTTING_TAGS) {
            stateMachine.currentTagIndex = com.gptgongjakso.naverwriterhelper.store.SessionRepository.tagController.doneCount
            if (com.gptgongjakso.naverwriterhelper.store.SessionRepository.tagController.hasNext()) {
                stateEnteredAt = clock() // 태그 1개당 타임아웃 기준 재설정(지시서 6.4)
                onUpdate()
                scheduleNext(STEP_GAP_MS) { tick() }
                return
            }
        }

        val target = nextOf(state)
        val advanced = stateMachine.advanceTo(target, result.evidence)
        if (!advanced) {
            stateMachine.pause("허용되지 않은 단계 전이 시도: ${state.name} → ${target.name}")
            running = false
            onUpdate()
            return
        }
        stateEnteredAt = clock()
        onUpdate()
        if (stateMachine.current.isHalting) {
            running = false
            onLog("자동 실행 완료 · ${stateMachine.current.name}")
            onUpdate()
            return
        }
        scheduleNext(STEP_GAP_MS) { tick() }
    }

    private fun handleRetryable(state: PipelineState, result: ActionResult.Retryable) {
        val elapsed = clock() - stateEnteredAt
        val budget = timeoutMsFor(state)
        if (elapsed >= budget) {
            stateMachine.pause("${state.name} 타임아웃(${budget}ms 초과): ${result.reason}")
            running = false
            onLog("일시정지 · 타임아웃(${state.name})")
            onUpdate()
            return
        }
        val canContinue = stateMachine.retryOrPause("$state 재시도 초과: ${result.reason}")
        onUpdate()
        if (canContinue) {
            scheduleNext(STEP_GAP_MS) { tick() }
        } else {
            running = false
            onLog("일시정지 · 재시도 초과(${state.name})")
        }
    }
}
