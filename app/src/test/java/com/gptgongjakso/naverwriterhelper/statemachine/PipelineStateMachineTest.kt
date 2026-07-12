package com.gptgongjakso.naverwriterhelper.statemachine

import com.gptgongjakso.naverwriterhelper.model.PipelineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 상태머신 단위 테스트 (작업지시서 14.1-1~6).
 * clock 을 주입해 시간 흐름을 결정적으로 제어한다(실제 Handler/Looper 불필요).
 */
class PipelineStateMachineTest {

    private fun machine(startAt: Long = 0L): Pair<PipelineStateMachine, LongArray> {
        val now = longArrayOf(startAt)
        val sm = PipelineStateMachine(clock = { now[0] })
        return sm to now
    }

    // 1. 상태머신 정상 순서
    @Test
    fun `advanceTo follows forward order`() {
        val (sm, _) = machine()
        assertTrue(sm.advanceTo(PipelineState.VALIDATING))
        assertEquals(PipelineState.VALIDATING, sm.current)
        assertTrue(sm.advanceTo(PipelineState.DUPLICATE_CHECKING))
        assertEquals(PipelineState.DUPLICATE_CHECKING, sm.current)
    }

    // 2. 허용되지 않은 단계 전이 차단
    @Test
    fun `advanceTo rejects skipping steps`() {
        val (sm, _) = machine()
        // RECEIVED -> INPUTTING_TITLE 은 인접 전이가 아니므로 거부되어야 한다.
        val ok = sm.advanceTo(PipelineState.INPUTTING_TITLE)
        assertFalse(ok)
        assertEquals(PipelineState.RECEIVED, sm.current) // 전이되지 않고 그대로 유지
    }

    // 3. 일시정지 후 정확한 단계 재개
    @Test
    fun `pause then resume returns to exact same state`() {
        val (sm, _) = machine()
        sm.advanceTo(PipelineState.VALIDATING)
        sm.advanceTo(PipelineState.DUPLICATE_CHECKING)
        sm.pause("테스트 일시정지")
        assertEquals(PipelineState.PAUSED, sm.current)
        assertEquals("테스트 일시정지", sm.pauseReason)

        val resumed = sm.resume()
        assertEquals(PipelineState.DUPLICATE_CHECKING, resumed)
        assertEquals(PipelineState.DUPLICATE_CHECKING, sm.current)
        assertEquals(null, sm.pauseReason)
    }

    // 4. 단계 타임아웃(순수 clock 기반 — AutomationOrchestrator 가 사용하는 방식과 동일한 원리)
    @Test
    fun `elapsed time can be measured against a budget via injected clock`() {
        val (sm, now) = machine(startAt = 1_000L)
        sm.advanceTo(PipelineState.VALIDATING)
        val enteredAt = now[0]
        now[0] = enteredAt + 20_000L // 20초 경과
        val elapsed = now[0] - enteredAt
        assertTrue("경과 시간이 타임아웃 예산을 넘었는지 판정 가능해야 함", elapsed >= 15_000L)
    }

    // 5. 최대 재시도 초과 후 PAUSED
    @Test
    fun `retryOrPause pauses after exceeding max retries`() {
        val (sm, _) = machine()
        sm.advanceTo(PipelineState.VALIDATING)
        assertTrue(sm.retryOrPause("재시도 1")) // 1회
        assertTrue(sm.retryOrPause("재시도 2")) // 2회 (MAX_RETRIES_PER_STEP=2)
        val stillOk = sm.retryOrPause("재시도 3") // 3회째 → 초과
        assertFalse(stillOk)
        assertEquals(PipelineState.PAUSED, sm.current)
    }

    // 6. 앱 재실행용 세션 직렬화·복원
    @Test
    fun `toPersistableMap and restoreFrom round-trip`() {
        val (sm, _) = machine()
        sm.advanceTo(PipelineState.VALIDATING)
        sm.advanceTo(PipelineState.DUPLICATE_CHECKING)
        sm.dataId = "abc123"
        sm.currentTagIndex = 3
        sm.selectedPhotoCount = 2
        sm.totalPhotoCount = 5
        sm.pause("직렬화 테스트")

        val map = sm.toPersistableMap()

        val (restored, _) = machine()
        restored.restoreFrom(map)

        assertEquals(PipelineState.PAUSED, restored.current)
        assertEquals(PipelineState.DUPLICATE_CHECKING, restored.resumeState)
        assertEquals("직렬화 테스트", restored.pauseReason)
        assertEquals("abc123", restored.dataId)
        assertEquals(3, restored.currentTagIndex)
        assertEquals(2, restored.selectedPhotoCount)
        assertEquals(5, restored.totalPhotoCount)
    }

    @Test
    fun `completed states are tracked for forward advances`() {
        val (sm, _) = machine()
        sm.advanceTo(PipelineState.VALIDATING)
        sm.advanceTo(PipelineState.DUPLICATE_CHECKING)
        val completed = sm.completedStatesSnapshot()
        assertTrue(completed.contains(PipelineState.RECEIVED))
        assertTrue(completed.contains(PipelineState.VALIDATING))
        assertFalse(completed.contains(PipelineState.DUPLICATE_CHECKING)) // 아직 다음으로 안 넘어감
    }

    @Test
    fun `terminal states cannot transition further`() {
        val (sm, _) = machine()
        sm.cancel()
        assertEquals(PipelineState.CANCELLED, sm.current)
        assertFalse(sm.advanceTo(PipelineState.VALIDATING))
        assertEquals(PipelineState.CANCELLED, sm.current)
    }
}
