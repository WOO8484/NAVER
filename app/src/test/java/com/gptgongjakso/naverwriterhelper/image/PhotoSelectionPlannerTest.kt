package com.gptgongjakso.naverwriterhelper.image

import com.gptgongjakso.naverwriterhelper.model.ImageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 사진 자동 선택 계획/검증 단위 테스트 (작업지시서 14.1-19~23). */
class PhotoSelectionPlannerTest {

    private fun entry(order: Int, role: ImageRole, name: String) = SavedImageEntry(
        sessionId = "s1", postId = "p1", originalName = "orig_$name", savedName = name,
        mediaStoreUri = "content://x/$name", role = role, orderIndex = order,
        savedAtMillis = 0L, sha256 = "hash-$name"
    )

    // 19. 이미지 1·5·10장 계획
    @Test
    fun `plan works for 1 image`() {
        val entries = listOf(entry(0, ImageRole.THUMBNAIL, "t.png"))
        val plan = PhotoSelectionPlanner.buildPlan(entries)
        assertEquals(1, plan.expectedCount)
        assertEquals(1, plan.items.size)
    }

    @Test
    fun `plan works for 5 images`() {
        val entries = (0..4).map {
            if (it == 0) entry(0, ImageRole.THUMBNAIL, "t.png") else entry(it, ImageRole.BODY, "b$it.jpg")
        }
        val plan = PhotoSelectionPlanner.buildPlan(entries)
        assertEquals(5, plan.expectedCount)
        assertEquals(5, plan.items.size)
    }

    @Test
    fun `plan works for 10 images`() {
        val entries = (0..9).map {
            if (it == 0) entry(0, ImageRole.THUMBNAIL, "t.png") else entry(it, ImageRole.BODY, "b$it.jpg")
        }
        val plan = PhotoSelectionPlanner.buildPlan(entries)
        assertEquals(10, plan.expectedCount)
    }

    // 20. 대표 → 본문 순서 유지
    @Test
    fun `thumbnail always comes first regardless of input order`() {
        val entries = listOf(
            entry(2, ImageRole.BODY, "b2.jpg"),
            entry(1, ImageRole.BODY, "b1.jpg"),
            entry(0, ImageRole.THUMBNAIL, "t.png")
        )
        val plan = PhotoSelectionPlanner.buildPlan(entries)
        assertTrue(plan.items.first().isThumbnail)
        assertEquals("t.png", plan.items.first().savedName)
        assertEquals(listOf("t.png", "b1.jpg", "b2.jpg"), plan.items.map { it.savedName })
    }

    // 21. 중복 사진 선택 방지
    @Test
    fun `duplicate selection is detected`() {
        val plan = PhotoSelectionPlanner.buildPlan(
            listOf(entry(0, ImageRole.THUMBNAIL, "t.png"), entry(1, ImageRole.BODY, "b1.jpg"))
        )
        val result = PhotoSelectionPlanner.verifySelection(plan, listOf("t.png", "t.png"))
        assertTrue(result is PhotoSelectionPlanner.VerifyResult.DuplicateSelection)
    }

    // 23. 선택 개수 불일치 시 중단
    @Test
    fun `final count mismatch is detected`() {
        val plan = PhotoSelectionPlanner.buildPlan(
            listOf(entry(0, ImageRole.THUMBNAIL, "t.png"), entry(1, ImageRole.BODY, "b1.jpg"))
        )
        val result = PhotoSelectionPlanner.verifyFinalCount(plan, 1)
        assertTrue(result is PhotoSelectionPlanner.VerifyResult.CountMismatch)
    }

    @Test
    fun `matching final count is ok`() {
        val plan = PhotoSelectionPlanner.buildPlan(
            listOf(entry(0, ImageRole.THUMBNAIL, "t.png"), entry(1, ImageRole.BODY, "b1.jpg"))
        )
        val result = PhotoSelectionPlanner.verifyFinalCount(plan, 2)
        assertEquals(PhotoSelectionPlanner.VerifyResult.Ok, result)
    }
}
