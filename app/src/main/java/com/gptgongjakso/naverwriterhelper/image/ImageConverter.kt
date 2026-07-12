package com.gptgongjakso.naverwriterhelper.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gptgongjakso.naverwriterhelper.dedup.ContentFingerprint
import java.io.ByteArrayOutputStream

/**
 * 이미지 형식 변환 (작업지시서 8.1). v1.1.0 신규.
 *
 * 입력 허용: PNG/JPG/JPEG/WEBP 등 Android가 안전하게 디코딩 가능한 이미지.
 * 저장 규칙:
 *  - PNG/JPG/JPEG 원본은 정상 디코딩되면 원본 형식 유지
 *  - WEBP 등 선택기에서 불안정한 형식은 알파 채널이 있으면 PNG, 없으면 JPEG(품질 95)로 변환
 *  - 디코딩 실패·0바이트·비정상 크기는 오류로 처리(예외로 앱을 죽이지 않음)
 *  - 원본/결과 SHA-256을 함께 기록하되 이미지 내용 자체는 로그에 남기지 않는다
 */
object ImageConverter {

    enum class OutputFormat { KEEP_ORIGINAL, PNG, JPEG }

    const val JPEG_QUALITY = 95

    /** 네이버 사진 선택기에서 불안정하다고 보는 입력 형식(원본 그대로 두지 않고 변환 대상) */
    private val UNSTABLE_SOURCE_EXTENSIONS = setOf("webp")
    private val KEEPABLE_SOURCE_EXTENSIONS = setOf("png", "jpg", "jpeg")

    data class ConversionResult(
        val success: Boolean,
        val format: OutputFormat,
        val bytes: ByteArray?,
        val mimeType: String,
        val extension: String,
        val originalSha256: String,
        val resultSha256: String?,
        val error: String? = null
    )

    /**
     * 원본 확장자와 알파 채널 유무만으로 출력 형식을 정하는 순수 판정 로직.
     * Bitmap 디코딩 없이 단위 테스트가 가능하도록 분리했다.
     */
    fun decideOutputFormat(sourceExtension: String, hasAlpha: Boolean): OutputFormat {
        val ext = sourceExtension.lowercase().removePrefix(".")
        return when {
            ext in KEEPABLE_SOURCE_EXTENSIONS && ext !in UNSTABLE_SOURCE_EXTENSIONS -> OutputFormat.KEEP_ORIGINAL
            hasAlpha -> OutputFormat.PNG
            else -> OutputFormat.JPEG
        }
    }

    fun isSupportedInput(extension: String): Boolean {
        val ext = extension.lowercase().removePrefix(".")
        return ext in KEEPABLE_SOURCE_EXTENSIONS || ext in UNSTABLE_SOURCE_EXTENSIONS
    }

    /** 바이트 배열을 디코딩해 필요 시 변환한다. 실패해도 예외를 던지지 않는다. */
    fun convert(bytes: ByteArray, originalName: String): ConversionResult {
        val originalHash = sha256(bytes)
        if (bytes.isEmpty()) {
            return ConversionResult(false, OutputFormat.KEEP_ORIGINAL, null, "", "", originalHash, null, "0바이트 파일")
        }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            return ConversionResult(
                false, OutputFormat.KEEP_ORIGINAL, null, "", "", originalHash, null,
                "디코딩 실패 또는 비정상 크기"
            )
        }
        val ext = originalName.substringAfterLast('.', "")
        val format = decideOutputFormat(ext, bitmap.hasAlpha())
        return try {
            when (format) {
                OutputFormat.KEEP_ORIGINAL -> ConversionResult(
                    true, format, bytes, mimeOf(ext), ext.lowercase(), originalHash, originalHash
                )
                OutputFormat.PNG -> {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    val outBytes = out.toByteArray()
                    ConversionResult(true, format, outBytes, "image/png", "png", originalHash, sha256(outBytes))
                }
                OutputFormat.JPEG -> {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    val outBytes = out.toByteArray()
                    ConversionResult(true, format, outBytes, "image/jpeg", "jpg", originalHash, sha256(outBytes))
                }
            }
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    fun sha256(bytes: ByteArray): String = ContentFingerprint.sha256(bytes)

    private fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }
}
