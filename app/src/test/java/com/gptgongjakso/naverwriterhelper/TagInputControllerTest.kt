package com.gptgongjakso.naverwriterhelper

import com.gptgongjakso.naverwriterhelper.service.TagInputController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 태그를 "1개씩" 진행하는지 검증한다. (한 번에 전체를 넘기지 않음)
 */
class TagInputControllerTest {

    @Test
    fun `한 개씩 순서대로 진행하고 완료된다`() {
        val c = TagInputController()
        c.reset(listOf("폭염특보", "장마특보", "기상청날씨"))

        assertEquals(3, c.total)
        assertEquals("0/3", c.progressText())

        assertTrue(c.hasNext())
        assertEquals("폭염특보", c.current())
        assertEquals("폭염특보", c.markCurrentDone())
        assertEquals("1/3", c.progressText())

        assertEquals("장마특보", c.current())
        c.markCurrentDone()
        assertEquals("2/3", c.progressText())

        assertEquals("기상청날씨", c.current())
        c.markCurrentDone()
        assertEquals("3/3", c.progressText())

        assertFalse(c.hasNext())
        assertNull(c.current())
        // 완료 후 추가 호출은 null
        assertNull(c.markCurrentDone())
    }

    @Test
    fun `빈 태그는 진행할 것이 없다`() {
        val c = TagInputController()
        c.reset(emptyList())
        assertTrue(c.isEmpty())
        assertFalse(c.hasNext())
        assertEquals("0/0", c.progressText())
    }

    @Test
    fun `reset 하면 커서가 초기화된다`() {
        val c = TagInputController()
        c.reset(listOf("a", "b"))
        c.markCurrentDone()
        assertEquals(1, c.cursor)
        c.reset(listOf("x", "y", "z"))
        assertEquals(0, c.cursor)
        assertEquals(3, c.total)
    }
}
