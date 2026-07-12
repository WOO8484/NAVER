package com.gptgongjakso.naverwriterhelper

import com.gptgongjakso.naverwriterhelper.parser.TagNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 태그 정규화 단위 테스트.
 * 작업지시서 26-2, 26-3 및 10장 규칙을 검증한다.
 */
class TagNormalizerTest {

    @Test
    fun `26-2 해시로 붙은 태그 분리`() {
        val result = TagNormalizer.normalize("#폭염특보#장마특보#기상청날씨#오늘날씨")
        assertEquals(listOf("폭염특보", "장마특보", "기상청날씨", "오늘날씨"), result)
    }

    @Test
    fun `26-3 공백으로 구분된 태그 5개`() {
        val result = TagNormalizer.normalize("폭염특보 장마특보 기상청날씨 오늘날씨 생활안전")
        assertEquals(listOf("폭염특보", "장마특보", "기상청날씨", "오늘날씨", "생활안전"), result)
    }

    @Test
    fun `줄바꿈과 해시 혼합`() {
        val result = TagNormalizer.normalize("#폭염특보\n#장마특보\n#기상청날씨\n#오늘날씨")
        assertEquals(listOf("폭염특보", "장마특보", "기상청날씨", "오늘날씨"), result)
    }

    @Test
    fun `쉼표 공백 해시 혼합`() {
        val result = TagNormalizer.normalize("#폭염특보, 장마특보,#기상청날씨 , 오늘날씨")
        assertEquals(listOf("폭염특보", "장마특보", "기상청날씨", "오늘날씨"), result)
    }

    @Test
    fun `중복 제거 순서 유지`() {
        val result = TagNormalizer.normalize("폭염특보 장마특보 폭염특보 오늘날씨 장마특보")
        assertEquals(listOf("폭염특보", "장마특보", "오늘날씨"), result)
    }

    @Test
    fun `특수문자 이모지 제거`() {
        val result = TagNormalizer.normalize("#폭염특보! #장마특보~ #기상청_날씨😀 #오늘날씨.")
        assertEquals(listOf("폭염특보", "장마특보", "기상청_날씨", "오늘날씨"), result)
    }

    @Test
    fun `placeholder 전체는 빈 리스트`() {
        assertTrue(TagNormalizer.normalize("정보 없음").isEmpty())
        assertTrue(TagNormalizer.normalize("#태그 없음").isEmpty())
        assertTrue(TagNormalizer.normalize("없음").isEmpty())
    }

    @Test
    fun `placeholder 토큰 제거`() {
        val result = TagNormalizer.normalize("폭염특보 없음 장마특보")
        assertEquals(listOf("폭염특보", "장마특보"), result)
    }

    @Test
    fun `빈 입력과 공백은 빈 리스트`() {
        assertTrue(TagNormalizer.normalize("").isEmpty())
        assertTrue(TagNormalizer.normalize(null).isEmpty())
        assertTrue(TagNormalizer.normalize("   \n  ").isEmpty())
    }

    @Test
    fun `너무 긴 붙은 값은 드롭`() {
        val longGarbage = "이건띄어쓰기없이아주아주아주아주아주아주아주아주길게붙어버린쓰레기값입니다다다다다"
        val result = TagNormalizer.normalize("정상태그 $longGarbage")
        assertEquals(listOf("정상태그"), result)
    }

    @Test
    fun `표시용 문자열은 해시 접두`() {
        val display = TagNormalizer.toDisplayString(listOf("폭염특보", "장마특보"))
        assertEquals("#폭염특보 #장마특보", display)
    }
}
