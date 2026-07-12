package com.gptgongjakso.naverwriterhelper.image

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ImageConverter.decideOutputFormat] 순수 판정 로직 테스트 (작업지시서 8.1, 14.1-22 관련).
 * 실제 Bitmap 디코딩(android.graphics)은 JVM 단위 테스트 환경에서 실행할 수 없으므로
 * (Robolectric 미사용, 지시서 범위 내 순수 로직만) 이 부분만 검증하고 나머지는 실기 확인이 필요하다.
 */
class ImageConverterFormatTest {

    @Test
    fun `png stays as original`() {
        assertEquals(ImageConverter.OutputFormat.KEEP_ORIGINAL, ImageConverter.decideOutputFormat("png", hasAlpha = true))
    }

    @Test
    fun `jpg stays as original even without alpha`() {
        assertEquals(ImageConverter.OutputFormat.KEEP_ORIGINAL, ImageConverter.decideOutputFormat("jpg", hasAlpha = false))
    }

    @Test
    fun `webp with alpha converts to png`() {
        assertEquals(ImageConverter.OutputFormat.PNG, ImageConverter.decideOutputFormat("webp", hasAlpha = true))
    }

    @Test
    fun `webp without alpha converts to jpeg`() {
        assertEquals(ImageConverter.OutputFormat.JPEG, ImageConverter.decideOutputFormat("webp", hasAlpha = false))
    }

    @Test
    fun `unknown extension without alpha falls back to jpeg`() {
        assertEquals(ImageConverter.OutputFormat.JPEG, ImageConverter.decideOutputFormat("bmp", hasAlpha = false))
    }

    @Test
    fun `isSupportedInput recognizes common formats`() {
        assertEquals(true, ImageConverter.isSupportedInput("PNG"))
        assertEquals(true, ImageConverter.isSupportedInput("webp"))
        assertEquals(false, ImageConverter.isSupportedInput("gif"))
    }
}
