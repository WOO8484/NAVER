package com.gptgongjakso.naverwriterhelper.image

import com.gptgongjakso.naverwriterhelper.model.ImageRole
import org.junit.Assert.assertEquals
import org.junit.Test

/** 이미지 저장 파일명 규칙 + manifest 직렬화 왕복 테스트 (작업지시서 8.2, 14.1-6 계열). */
class SavedImageManifestTest {

    @Test
    fun `buildSaveName follows GPTG prefix format`() {
        val name = SavedImageManifest.buildSaveName("post123", "sess456", 0, ImageRole.THUMBNAIL, "png")
        assertEquals("GPTG_post123_sess456_00_thumbnail.png", name)
    }

    @Test
    fun `buildSaveName pads order index and lowercases extension`() {
        val name = SavedImageManifest.buildSaveName("p", "s", 3, ImageRole.BODY, "JPG")
        assertEquals("GPTG_p_s_03_body.jpg", name)
    }

    @Test
    fun `toJson and fromJson round-trip`() {
        val entries = listOf(
            SavedImageEntry("s1", "p1", "orig.png", "GPTG_p1_s1_00_thumbnail.png", "content://x/1", ImageRole.THUMBNAIL, 0, 1000L, "hash1"),
            SavedImageEntry("s1", "p1", "orig2.jpg", "GPTG_p1_s1_01_body.jpg", "content://x/2", ImageRole.BODY, 1, 2000L, "hash2")
        )
        val json = SavedImageManifest.toJson(entries)
        val restored = SavedImageManifest.fromJson(json)
        assertEquals(2, restored.size)
        assertEquals(entries[0].savedName, restored[0].savedName)
        assertEquals(entries[1].sha256, restored[1].sha256)
        assertEquals(ImageRole.BODY, restored[1].role)
    }

    @Test
    fun `fromJson on broken input returns empty list, never throws`() {
        val restored = SavedImageManifest.fromJson("not json")
        assertEquals(0, restored.size)
    }
}
