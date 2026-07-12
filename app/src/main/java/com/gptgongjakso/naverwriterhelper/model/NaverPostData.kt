package com.gptgongjakso.naverwriterhelper.model

/**
 * GPT 공작소가 만든 네이버용 자료 한 세트.
 *
 * v0.1.1(제목/본문/태그/이미지)을 이식하고, v1.0.0 에서 다음을 확장한다.
 *  - metadata(schema 2.1): 게시판/이미지 범위/주제 등
 *  - 해시(zipSha256/bodySha256/bodyFingerprint): 중복 검사(지시서 18)
 *  - 가변 이미지: 5장 고정 대신 metadata/게시판 프로필의 권장값 사용(지시서 7)
 */
data class NaverPostData(
    val title: String,
    val body: String,
    val tags: List<String>,
    val images: List<ParsedImage>,
    val metadata: PostMetadata = PostMetadata.EMPTY,
    /** ZIP 원본 SHA-256 (지시서 8/18). 파싱 시 계산. */
    val zipSha256: String = "",
    /** 본문 SHA-256 */
    val bodySha256: String = "",
    /** 본문 지문(정규화 후 해시) */
    val bodyFingerprint: String = ""
) {
    val titleLength: Int get() = title.length
    val bodyLength: Int get() = body.length
    val tagCount: Int get() = tags.size
    val imageCount: Int get() = images.size
    val thumbnailCount: Int get() = images.count { it.role == ImageRole.THUMBNAIL }
    val bodyImageCount: Int get() = images.count { it.role == ImageRole.BODY }

    /**
     * 권장 이미지 장수. metadata.image_recommended → 기본 5.
     * (지시서 7: 게시판 프로필에서 조정 가능. 최종 판정은 ImageValidator + BoardProfile)
     */
    val recommendedImageCount: Int
        get() = metadata.imageRecommended ?: DEFAULT_RECOMMENDED_IMAGE_COUNT

    /** 권장 대비 부족한 장수(음수면 0). 화면 보조 표시용. */
    val missingImageCount: Int
        get() = (recommendedImageCount - imageCount).coerceAtLeast(0)

    companion object {
        /** 기본 권장 이미지 장수 (썸네일 1 + 본문 4). 게시판/메타데이터가 없을 때만 사용. */
        const val DEFAULT_RECOMMENDED_IMAGE_COUNT = 5
    }
}
