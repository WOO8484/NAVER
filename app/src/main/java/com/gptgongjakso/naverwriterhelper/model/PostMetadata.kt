package com.gptgongjakso.naverwriterhelper.model

/**
 * metadata.json 스키마 2.1 (지시서 9). v1.0.0 신규.
 *
 * 모든 필드는 nullable 이며, 구버전/누락 시 안전한 기본값으로 대체된다.
 * 구버전 변환은 [com.gptgongjakso.naverwriterhelper.parser.MetadataMapper] 에서 처리한다.
 */
data class PostMetadata(
    val schemaVersion: String = "2.1",
    val postId: String? = null,
    val title: String? = null,
    val naverCategory: String? = null,
    val tags: List<String> = emptyList(),
    val imageCount: Int? = null,
    val imageMin: Int? = null,
    val imageRecommended: Int? = null,
    val imageMax: Int? = null,
    val contentVersion: Int = 1,
    val topicKey: String? = null,
    val topicAngle: String? = null,
    val instructionVersion: String? = null
) {
    companion object {
        /** metadata 없이 파싱된 경우의 빈 메타데이터 */
        val EMPTY = PostMetadata()
    }
}
