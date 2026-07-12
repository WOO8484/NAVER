package com.gptgongjakso.naverwriterhelper.automation

import com.gptgongjakso.naverwriterhelper.board.BoardProfileRepository
import com.gptgongjakso.naverwriterhelper.model.PipelineState
import com.gptgongjakso.naverwriterhelper.statemachine.PipelineStateMachine
import com.gptgongjakso.naverwriterhelper.store.SessionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AutomationOrchestrator] 순서·재시도·타임아웃·일시정지·이어하기·즉시중지 단위 테스트
 * (작업지시서 14.1). [FakeAutomationStepExecutor] 와 동기 실행 스케줄러를 사용해
 * 실제 AccessibilityService/Handler 없이 순수 JVM에서 검증한다.
 */
class AutomationOrchestratorTest {

    private val syncScheduler: (Long, () -> Unit) -> Unit = { _, action -> action() }

    @Before
    fun resetGlobalState() {
        SessionRepository.tagController.reset(emptyList())
        SessionRepository.manualCategorySelection = null
    }

    private fun readyToAutomate(): PipelineStateMachine {
        val sm = PipelineStateMachine()
        val order = sm.forwardOrderSnapshot()
        val targetIdx = order.indexOf(PipelineState.READY_TO_AUTOMATE)
        for (i in 1..targetIdx) sm.advanceTo(order[i])
        return sm
    }

    private fun newOrchestrator(
        sm: PipelineStateMachine,
        executor: FakeAutomationStepExecutor,
        naverCategory: String? = "생활정보",
        expectedPhotos: Int = 0,
        clock: () -> Long = { 0L },
        timeoutMsFor: (PipelineState) -> Long = { 10_000L },
        onLog: (String) -> Unit = {}
    ) = AutomationOrchestrator(
        stateMachine = sm,
        executor = executor,
        boardProfile = { BoardProfileRepository.byKey("living_info") },
        naverCategory = { naverCategory },
        expectedPhotoCount = { expectedPhotos },
        scheduleNext = syncScheduler,
        onLog = onLog,
        clock = clock,
        timeoutMsFor = timeoutMsFor
    )

    @Test
    fun `happy path with photos and tags reaches READY_FOR_USER`() {
        SessionRepository.tagController.reset(listOf("tag1", "tag2"))
        val sm = readyToAutomate()
        val executor = FakeAutomationStepExecutor().apply {
            categoryTexts = listOf("생활정보")
            selectedPhotoCountToReport = 2
        }
        val orchestrator = newOrchestrator(sm, executor, expectedPhotos = 2)

        orchestrator.start()

        assertEquals(PipelineState.READY_FOR_USER, sm.current)
        assertFalse(orchestrator.running)
        assertFalse(SessionRepository.tagController.hasNext())
        assertEquals(2, sm.selectedPhotoCount)
        // 발행/임시저장 계열 호출이 전혀 없어야 한다(안전 원칙 회귀 확인)
        assertTrue(executor.callLog.none { it.contains("publish", ignoreCase = true) })
    }

    @Test
    fun `no photos and no tags still reaches READY_FOR_USER`() {
        val sm = readyToAutomate()
        val executor = FakeAutomationStepExecutor().apply { categoryTexts = listOf("생활정보") }
        val orchestrator = newOrchestrator(sm, executor, expectedPhotos = 0)

        orchestrator.start()

        assertEquals(PipelineState.READY_FOR_USER, sm.current)
    }

    @Test
    fun `retryable exceeding max retries pauses`() {
        val sm = readyToAutomate()
        val executor = FakeAutomationStepExecutor().apply {
            openNaverResult = ActionResult.Retryable("네이버 실행 실패")
        }
        val orchestrator = newOrchestrator(sm, executor)

        orchestrator.start()

        assertEquals(PipelineState.PAUSED, sm.current)
        assertFalse(orchestrator.running)
        // MAX_RETRIES_PER_STEP(2) 초과 후 정지 → OPENING_NAVER 시도 횟수는 3회(최초+재시도2회)
        assertEquals(3, executor.callLog.count { it == "openNaverBlogApp" })
    }

    @Test
    fun `step timeout pauses even with retries remaining`() {
        val sm = readyToAutomate()
        val executor = FakeAutomationStepExecutor().apply {
            openNaverResult = ActionResult.Retryable("응답 없음")
        }
        // 호출할 때마다 1초씩 흐르는 가짜 시계 → 첫 재시도 판정 시점에 이미 예산(500ms)을 초과한다.
        var callCount = 0L
        val clock = { callCount++; callCount * 1_000L }
        val orchestrator = newOrchestrator(
            sm, executor,
            clock = clock,
            timeoutMsFor = { 500L } // 아주 짧은 타임아웃
        )

        orchestrator.start()

        assertEquals(PipelineState.PAUSED, sm.current)
        assertTrue(sm.pauseReason?.contains("타임아웃") == true)
        // 타임아웃으로 즉시 정지하므로 openNaverBlogApp 은 1회만 호출된다(재시도 없음).
        assertEquals(1, executor.callLog.count { it == "openNaverBlogApp" })
    }

    @Test
    fun `blocked result pauses immediately without extra retries`() {
        val sm = readyToAutomate()
        val executor = FakeAutomationStepExecutor().apply {
            categoryTexts = listOf("생활정보")
            selectedPhotoCountToReport = 0
            confirmPhotoResult = ActionResult.Blocked("위험 버튼 근접")
        }
        val orchestrator = newOrchestrator(sm, executor, expectedPhotos = 0)

        orchestrator.start()

        assertEquals(PipelineState.PAUSED, sm.current)
        assertTrue(sm.pauseReason?.contains("안전 차단") == true)
        // Blocked 는 재시도하지 않으므로 confirmPhotoSelection 호출은 1회만 있어야 한다.
        assertEquals(1, executor.callLog.count { it == "confirmPhotoSelection" })
    }

    @Test
    fun `board not found pauses for manual pick then records user selection on resume`() {
        val sm = readyToAutomate()
        val executor = FakeAutomationStepExecutor().apply {
            categoryTexts = emptyList() // 자동 매칭 실패 유도
        }
        val orchestrator = newOrchestrator(sm, executor, naverCategory = "존재하지않는게시판")

        orchestrator.start()
        assertEquals(PipelineState.PAUSED, sm.current)
        assertTrue(sm.pauseReason?.contains("게시판") == true)

        // 사용자가 네이버 화면에서 직접 게시판을 선택했다고 가정하고 화면에 표시된 값을 세팅
        executor.displayedCategory = "생활정보"
        orchestrator.resumeNow()

        assertEquals(PipelineState.READY_FOR_USER, sm.current)
        assertEquals("생활정보", SessionRepository.manualCategorySelection)
    }

    @Test
    fun `cancelNow stops running and sets CANCELLED`() {
        val sm = readyToAutomate()
        val executor = FakeAutomationStepExecutor().apply {
            categoryTexts = listOf("생활정보")
            // 사진 선택 단계에서 멈춰있게 만들어 실행 중 취소를 시뮬레이션
            openPhotoButtonResult = ActionResult.NeedsUser("대기")
        }
        val orchestrator = newOrchestrator(sm, executor, expectedPhotos = 1)
        orchestrator.start()
        assertEquals(PipelineState.PAUSED, sm.current) // NeedsUser 로 일시정지된 상태

        orchestrator.cancelNow()
        assertEquals(PipelineState.CANCELLED, sm.current)
        assertFalse(orchestrator.running)
    }

    @Test
    fun `pauseNow followed by resumeNow continues from same state`() {
        val sm = readyToAutomate()
        val executor = FakeAutomationStepExecutor().apply {
            categoryTexts = listOf("생활정보")
        }
        val orchestrator = newOrchestrator(sm, executor)
        // NeedsUser 로 자연 정지시키기 위해 제목 입력을 사용자 필요로 설정
        executor.inputTitleResult = ActionResult.NeedsUser("입력칸을 찾지 못함")
        orchestrator.start()
        assertEquals(PipelineState.PAUSED, sm.current)
        val pausedAt = sm.resumeState
        assertEquals(PipelineState.INPUTTING_TITLE, pausedAt)

        executor.inputTitleResult = ActionResult.Success("이제 성공")
        orchestrator.resumeNow()
        assertEquals(PipelineState.READY_FOR_USER, sm.current)
    }
}
