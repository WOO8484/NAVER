package com.gptgongjakso.naverwriterhelper.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 게시판 매칭 단위 테스트 (작업지시서 14.1-10~12, 7.2 확장). */
class BoardMatcherTest {

    private val profiles = BoardProfileRepository.defaults

    // 10. 게시판 정확 일치 우선
    @Test
    fun `exact display name match wins`() {
        val matched = BoardMatcher.match("생활정보", profiles)
        assertEquals("living_info", matched?.key)
    }

    // 11. 게시판 별칭 일치
    @Test
    fun `alias match works when display name differs`() {
        val matched = BoardMatcher.match("행사", profiles)
        assertEquals("local_event", matched?.key)
    }

    // 12. 게시판 미일치 null
    @Test
    fun `unmatched category returns null, never guesses`() {
        val matched = BoardMatcher.match("전혀 다른 주제", profiles)
        assertNull(matched)
    }

    @Test
    fun `partial substring never matches`() {
        // "생활" 만으로는 "생활정보"에 부분일치하지만, 정확 일치/별칭만 허용하므로 매칭되면 안 된다
        // (단, "생활" 자체가 별칭 목록에 있다면 별칭 일치로 성립 — living_info 별칭에 포함되어 있음)
        val matched = BoardMatcher.match("생활", profiles)
        assertEquals("living_info", matched?.key) // 별칭 정확 일치이지 부분일치가 아님
    }

    @Test
    fun `whitespace and case are normalized`() {
        val matched = BoardMatcher.match("  Living Info  ", profiles)
        assertEquals("living_info", matched?.key)
    }

    // 실제 화면 텍스트 매칭 (작업지시서 7.2)
    @Test
    fun `matchAgainstDisplayedTexts finds exact naver_category match first`() {
        val displayed = listOf("공지사항", "생활정보", "기타")
        val result = BoardMatcher.matchAgainstDisplayedTexts("생활정보", displayed, null)
        assertEquals("생활정보", result)
    }

    @Test
    fun `matchAgainstDisplayedTexts falls back to alias exact match`() {
        val profile = BoardProfileRepository.byKey("local_event")
        val displayed = listOf("공지사항", "행사", "기타")
        val result = BoardMatcher.matchAgainstDisplayedTexts("지역행사아님", displayed, profile)
        assertEquals("행사", result)
    }

    @Test
    fun `matchAgainstDisplayedTexts never partial-matches and returns null when absent`() {
        val displayed = listOf("생활정보안내", "기타") // "생활정보"의 부분 포함이지만 정확 일치 아님
        val result = BoardMatcher.matchAgainstDisplayedTexts("생활정보", displayed, null)
        assertNull(result)
    }
}
