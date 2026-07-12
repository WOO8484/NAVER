package com.gptgongjakso.naverwriterhelper.dedup

import java.security.MessageDigest

/**
 * 콘텐츠 해시/지문 계산 (지시서 18). v1.0.0 신규.
 *
 * - sha256(): 바이트/문자열의 SHA-256 (ZIP 원본·본문 원문 해시)
 * - fingerprint(): 본문을 정규화(공백/구두점/대소문자 제거)한 뒤의 SHA-256.
 *   사소한 편집(띄어쓰기/줄바꿈)만 다른 유사 글을 "중복 의심"으로 잡기 위한 지문.
 *
 * java.security.MessageDigest 만 사용 → Android 의존성 없이 단위 테스트 가능.
 */
object ContentFingerprint {

    /** 바이트 배열의 SHA-256 (소문자 hex) */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** 문자열의 SHA-256 (UTF-8, 소문자 hex) */
    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    /**
     * 본문 지문. 정규화 규칙:
     *  - 모든 공백류(스페이스/탭/줄바꿈) 제거
     *  - 구두점/특수문자 제거(한글/영문/숫자만 유지)
     *  - 소문자화
     * 그런 뒤 SHA-256.
     */
    fun fingerprint(body: String): String {
        val normalized = body
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
        return sha256(normalized)
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
