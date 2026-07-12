package com.gptgongjakso.naverwriterhelper.automation

import com.gptgongjakso.naverwriterhelper.model.PipelineState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutomationSessionStore] 의 Context 없이 검증 가능한 순수 로직 테스트.
 * save()/load() 자체는 SharedPreferences(Android)가 필요해 계측 테스트(실기) 영역이며,
 * 여기서는 이어하기 판정 로직과 단일 세션 잠금만 검증한다(작업지시서 14.1-6, 14.1-24).
 */
class AutomationSessionStoreLogicTest {

    @After
    fun releaseLockAfterEachTest() {
        AutomationSessionStore.releaseLock()
    }

    @Test
    fun `terminal states are not treated as interrupted`() {
        assertFalse(AutomationSessionStore.isInterruptedState(PipelineState.COMPLETED_BY_USER.name))
        assertFalse(AutomationSessionStore.isInterruptedState(PipelineState.CANCELLED.name))
        assertFalse(AutomationSessionStore.isInterruptedState(PipelineState.RECEIVED.name))
        assertFalse(AutomationSessionStore.isInterruptedState(""))
    }

    @Test
    fun `mid-automation states are treated as interrupted`() {
        assertTrue(AutomationSessionStore.isInterruptedState(PipelineState.INPUTTING_TITLE.name))
        assertTrue(AutomationSessionStore.isInterruptedState(PipelineState.PAUSED.name))
        assertTrue(AutomationSessionStore.isInterruptedState(PipelineState.SELECTING_PHOTOS.name))
    }

    // 24. 한 번에 하나의 자동화 세션만 허용
    @Test
    fun `only one automation session lock can be held at a time`() {
        assertTrue(AutomationSessionStore.tryAcquireLock())
        assertFalse(AutomationSessionStore.tryAcquireLock()) // 두 번째 시도는 거부되어야 함
        assertTrue(AutomationSessionStore.isLocked())

        AutomationSessionStore.releaseLock()
        assertFalse(AutomationSessionStore.isLocked())
        assertTrue(AutomationSessionStore.tryAcquireLock()) // 해제 후에는 다시 획득 가능
    }
}
