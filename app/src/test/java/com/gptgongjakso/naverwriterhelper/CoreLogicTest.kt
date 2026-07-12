package com.gptgongjakso.naverwriterhelper

import com.gptgongjakso.naverwriterhelper.board.BoardMatcher
import com.gptgongjakso.naverwriterhelper.board.BoardProfileRepository
import com.gptgongjakso.naverwriterhelper.dedup.ContentFingerprint
import com.gptgongjakso.naverwriterhelper.dedup.DuplicateChecker
import com.gptgongjakso.naverwriterhelper.image.ImageValidator
import com.gptgongjakso.naverwriterhelper.model.DuplicateVerdict
import com.gptgongjakso.naverwriterhelper.model.ImageRole
import com.gptgongjakso.naverwriterhelper.model.ParsedImage
import com.gptgongjakso.naverwriterhelper.model.PipelineState
import com.gptgongjakso.naverwriterhelper.model.PostHistoryRecord
import com.gptgongjakso.naverwriterhelper.parser.MetadataMapper
import com.gptgongjakso.naverwriterhelper.statemachine.PipelineStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.0 신규 순수 로직 단위 테스트 (지시서 7/9/10/11/18).
 * Android 의존성이 없어 로컬 JVM 에서 실행된다. (org.json 미사용)
 */
class CoreLogicTest {

    private fun img(role: ImageRole, name: String, bytes: ByteArray, order: Int = 0) =
        ParsedImage(role, name, "save_$name", bytes, "image/png", order)

    private val now = 1_000_000_000_000L

    private fun rec(id: String, zip: String, body: String, fp: String, title: String,
                    topic: String?, angle: String?, cv: Int, ageDays: Int) =
        PostHistoryRecord(id, title, "body", "government_support", topic, angle,
            emptyList(), now - ageDays.toLong() * 86_400_000L, "발행완료", zip, body, fp, cv, "정상")

    // ---------------- MetadataMapper ----------------
    @Test fun `metadata 2_1 매핑`() {
        val m = MetadataMapper.fromMap(mapOf(
            "schema_version" to "2.1", "post_id" to "p1", "naver_category" to "정부지원",
            "tags" to listOf("a", "b"), "image_max" to 10, "content_version" to 2
        ))
        assertEquals("p1", m.postId)
        assertEquals("정부지원", m.naverCategory)
        assertEquals(10, m.imageMax)
        assertEquals(2, m.contentVersion)
        assertEquals(listOf("a", "b"), m.tags)
    }

    @Test fun `구버전 metadata 승격`() {
        val m = MetadataMapper.fromMap(mapOf("id" to "old", "category" to "생활정보", "tags" to "생활 정보"))
        assertEquals("1.0", m.schemaVersion)
        assertEquals("생활정보", m.naverCategory)
        assertEquals(listOf("생활", "정보"), m.tags)
        assertEquals(1, m.contentVersion)
    }

    // ---------------- ContentFingerprint ----------------
    @Test fun `SHA-256 알려진 벡터`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentFingerprint.sha256(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentFingerprint.sha256("abc"))
    }

    @Test fun `지문은 공백_구두점 차이를 무시`() {
        val a = ContentFingerprint.fingerprint("안녕하세요. 오늘 날씨!")
        val b = ContentFingerprint.fingerprint("안녕하세요  오늘   날씨")
        assertEquals(a, b)
        assertNotEquals(a, ContentFingerprint.fingerprint("다른 내용"))
    }

    // ---------------- DuplicateChecker ----------------
    @Test fun `동일 ZIP 또는 본문은 IDENTICAL`() {
        val ex = listOf(rec("p1", "ZIP1", "BODY1", "FP1", "제목", "t", "a", 1, 2))
        assertEquals(DuplicateVerdict.IDENTICAL,
            DuplicateChecker.check("ZIP1", "X", "Y", "무", "t", "a", 1, null, ex, now, 30).verdict)
        assertEquals(DuplicateVerdict.IDENTICAL,
            DuplicateChecker.check("Z", "BODY1", "Y", "무", "t", "a", 1, null, ex, now, 30).verdict)
    }

    @Test fun `지문 일치는 SUSPECT`() {
        val ex = listOf(rec("p1", "ZIP1", "BODY1", "FP1", "제목", "t", "a", 1, 2))
        assertEquals(DuplicateVerdict.SUSPECT,
            DuplicateChecker.check("Z", "NEW", "FP1", "무", "t", "a", 1, null, ex, now, 30).verdict)
    }

    @Test fun `기간 내 동일 주제는 CAUTION, 기간 밖은 NORMAL`() {
        val ex = listOf(rec("p2", "ZIP2", "BODY2", "FP2", "제목", "절약", "방법", 1, 40))
        // 40일 전, window 30 → 기간 밖 → 정상
        assertEquals(DuplicateVerdict.NORMAL,
            DuplicateChecker.check("Z", "NB", "NFP", "무", "절약", "방법", 1, null, ex, now, 30).verdict)
        // window 60 → 기간 안 → 주의
        assertEquals(DuplicateVerdict.CAUTION,
            DuplicateChecker.check("Z", "NB", "NFP", "무", "절약", "방법", 1, null, ex, now, 60).verdict)
    }

    @Test fun `수정본은 동일 글이 아니다`() {
        val ex = listOf(rec("p1", "ZIP1", "BODY1", "FP1", "제목", "t", "a", 1, 2))
        val r = DuplicateChecker.check("ZIP1", "DIFF", "DIFFFP", "제목", "t", "a", 2, "p1", ex, now, 30)
        assertNotEquals(DuplicateVerdict.IDENTICAL, r.verdict)
    }

    // ---------------- BoardMatcher ----------------
    @Test fun `게시판 정확_별칭 매칭과 미매칭`() {
        val p = BoardProfileRepository.defaults
        assertEquals(9, p.size)
        assertEquals("government_support", BoardMatcher.match("정부지원", p)?.key)
        assertEquals("government_support", BoardMatcher.match("지원금", p)?.key)
        assertNull(BoardMatcher.match("없는게시판", p))
    }

    // ---------------- ImageValidator ----------------
    @Test fun `이미지 장수 범위 판정`() {
        val gov = BoardProfileRepository.byKey("government_support")!!
        val ok5 = listOf(
            img(ImageRole.THUMBNAIL, "t.png", byteArrayOf(1)),
            img(ImageRole.BODY, "b1.png", byteArrayOf(2), 1),
            img(ImageRole.BODY, "b2.png", byteArrayOf(3), 2),
            img(ImageRole.BODY, "b3.png", byteArrayOf(4), 3),
            img(ImageRole.BODY, "b4.png", byteArrayOf(5), 4)
        )
        assertEquals(ImageValidator.Severity.NORMAL, ImageValidator.validate(ok5, gov, 5).severity)
        assertEquals(ImageValidator.Severity.CAUTION, ImageValidator.validate(ok5.take(3), gov, null).severity)
        val broken = ok5.dropLast(1) + img(ImageRole.BODY, "b4.png", ByteArray(0), 4)
        assertEquals(ImageValidator.Severity.ERROR, ImageValidator.validate(broken, gov, null).severity)
    }

    // ---------------- PipelineStateMachine ----------------
    @Test fun `상태머신 진행_일시정지_재개_실패`() {
        var t = 0L
        val sm = PipelineStateMachine { t += 10; t }
        assertEquals(PipelineState.RECEIVED, sm.current)
        sm.advance()
        assertEquals(PipelineState.VALIDATING, sm.current)
        sm.pause("전화")
        assertEquals(PipelineState.PAUSED, sm.current)
        assertEquals(PipelineState.VALIDATING, sm.resumeState)
        sm.resume()
        assertEquals(PipelineState.VALIDATING, sm.current)
        sm.fail("오류")
        assertEquals(PipelineState.FAILED, sm.current)
        sm.advance() // 종료 상태에서 전이 불가
        assertEquals(PipelineState.FAILED, sm.current)
    }

    @Test fun `종료_정지 상태 플래그`() {
        assertTrue(PipelineState.COMPLETED_BY_USER.isTerminal)
        assertTrue(PipelineState.READY_FOR_USER.isHalting)
        assertTrue(DuplicateVerdict.IDENTICAL.blocksAutoProceed)
    }
}
