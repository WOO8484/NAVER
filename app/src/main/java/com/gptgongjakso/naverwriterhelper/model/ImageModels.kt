package com.gptgongjakso.naverwriterhelper.model

/**
 * 이미지 역할 구분. (v0.1.1 이식)
 * THUMBNAIL = 대표 이미지, BODY = 본문 이미지
 */
enum class ImageRole { THUMBNAIL, BODY }

/**
 * 패키지에서 파싱한 이미지 1장. (v0.1.1 이식)
 * data class 대신 일반 class 사용(ByteArray equals 경고 회피).
 */
class ParsedImage(
    val role: ImageRole,
    /** 원본 파일명 (예: body-01.png) */
    val originalName: String,
    /** 갤러리 저장 시 사용할 파일명 (예: gptgongjakso_body_01.png) */
    val saveName: String,
    /** 이미지 원본 바이트 */
    val bytes: ByteArray,
    /** MIME 타입 (예: image/png) */
    val mimeType: String,
    /**
     * 본문 이미지 순서 인덱스(썸네일은 0). 순서·중복 검사에 사용.
     * v1.0.0: 이미지 순서/역할 매핑 검사를 위해 추가.
     */
    val orderIndex: Int = 0
) {
    /** 콘텐츠 동일성 비교용 바이트 길이(손상/중복 검사 보조) */
    val byteSize: Int get() = bytes.size
}
