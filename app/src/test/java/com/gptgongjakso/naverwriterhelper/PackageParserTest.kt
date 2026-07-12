package com.gptgongjakso.naverwriterhelper

import com.gptgongjakso.naverwriterhelper.parser.PackageParseException
import com.gptgongjakso.naverwriterhelper.parser.PackageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PackageParser ZIP 자원 제한 및 비정상 ZIP 방어 테스트 (v0.1.1 / 지시서 5-2).
 * 테스트 ZIP에는 metadata.json 을 넣지 않아 로컬 JVM 단위 테스트에서 org.json 을 호출하지 않는다.
 */
class PackageParserTest {

    private fun txt(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun zipOf(
        entries: List<Pair<String, ByteArray>>,
        level: Int = Deflater.DEFAULT_COMPRESSION
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.setLevel(level)
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun parse(bytes: ByteArray) =
        PackageParser.parse(ByteArrayInputStream(bytes))

    @Test
    fun `정상 5장 ZIP 통과`() {
        val zip = zipOf(
            listOf(
                "title.txt" to txt("제목입니다"),
                "content.txt" to txt("본문 내용입니다."),
                "tags.txt" to txt("#폭염특보#장마특보#기상청날씨#오늘날씨#생활안전"),
                "thumbnail.png" to txt("t"),
                "body-01.png" to txt("1"),
                "body-02.png" to txt("2"),
                "body-03.png" to txt("3"),
                "body-04.png" to txt("4")
            )
        )
        val d = parse(zip)
        assertEquals(5, d.imageCount)
        assertEquals(listOf("폭염특보", "장마특보", "기상청날씨", "오늘날씨", "생활안전"), d.tags)
        assertEquals(0, d.missingImageCount)
    }

    @Test
    fun `이미지 3장 ZIP 통과 및 부족 표시`() {
        val zip = zipOf(
            listOf(
                "title.txt" to txt("제목"),
                "content.txt" to txt("본문"),
                "tags.txt" to txt("폭염특보 장마특보 기상청날씨"),
                "thumbnail.png" to txt("t"),
                "body-01.png" to txt("1"),
                "body-02.png" to txt("2")
            )
        )
        val d = parse(zip)
        assertEquals(3, d.imageCount)
        assertEquals(2, d.missingImageCount)
    }

    @Test(expected = PackageParseException::class)
    fun `전체 엔트리 51개 초과 거부`() {
        val list = ArrayList<Pair<String, ByteArray>>()
        list.add("title.txt" to txt("t"))
        for (i in 1..51) list.add("junk-%03d.dat".format(i) to txt("j"))
        parse(zipOf(list))
    }

    @Test(expected = PackageParseException::class)
    fun `텍스트 2MB 초과 거부`() {
        val big = ByteArray(2 * 1024 * 1024 + 16) { 'a'.code.toByte() }
        parse(zipOf(listOf("content.txt" to big, "title.txt" to txt("t"))))
    }

    @Test(expected = PackageParseException::class)
    fun `단일 엔트리 25MB 초과 거부`() {
        val big = ByteArray(25 * 1024 * 1024 + 1024) { (it % 251).toByte() }
        parse(zipOf(listOf("body-01.png" to big), level = Deflater.NO_COMPRESSION))
    }

    @Test(expected = PackageParseException::class)
    fun `전체 해제 100MB 초과 거부`() {
        val rnd = Random(42)
        val list = ArrayList<Pair<String, ByteArray>>()
        for (i in 1..6) {
            val chunk = ByteArray(20 * 1024 * 1024) { (rnd.nextInt() and 0x03).toByte() }
            list.add("body-%02d.png".format(i) to chunk)
        }
        parse(zipOf(list, level = Deflater.BEST_COMPRESSION))
    }

    @Test(expected = PackageParseException::class)
    fun `중복 basename 거부`() {
        parse(zipOf(listOf("title.txt" to txt("t"), "sub/title.txt" to txt("t2"))))
    }

    @Test(expected = PackageParseException::class)
    fun `비정상 고압축률 거부`() {
        val zeros = ByteArray(3 * 1024 * 1024)
        parse(zipOf(listOf("body-01.png" to zeros, "title.txt" to txt("t")), level = Deflater.BEST_COMPRESSION))
    }

    @Test(expected = PackageParseException::class)
    fun `이미지 11개 초과 거부`() {
        val list = ArrayList<Pair<String, ByteArray>>()
        list.add("thumbnail.png" to txt("th"))
        for (i in 1..10) list.add("body-%02d.png".format(i) to txt("b$i"))
        parse(zipOf(list))
    }

    @Test(expected = PackageParseException::class)
    fun `빈 ZIP 거부`() {
        parse(zipOf(emptyList()))
    }

    @Test
    fun `손상 ZIP은 앱 강제 종료 없이 예외 처리`() {
        val garbage = ByteArray(2000) { (it % 200).toByte() }
        var threw = false
        try {
            parse(garbage)
        } catch (e: Throwable) {
            threw = true // 예외로 안전 중단(앱 크래시 아님)
        }
        assertTrue("손상 ZIP은 예외로 안전하게 중단되어야 함", threw)
    }

    @Test(expected = PackageParseException::class)
    fun `제어문자 파일명 거부`() {
        parse(zipOf(listOf("ti\u0007tle.txt" to txt("t"), "content.txt" to txt("c"))))
    }
}
