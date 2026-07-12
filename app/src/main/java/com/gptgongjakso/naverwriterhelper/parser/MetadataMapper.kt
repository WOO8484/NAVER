package com.gptgongjakso.naverwriterhelper.parser

import com.gptgongjakso.naverwriterhelper.model.PostMetadata

/**
 * metadata.json → PostMetadata 매핑 + 구버전 변환 계층 (지시서 9). v1.0.0 신규.
 *
 * 설계: JSON 파싱(org.json) 은 [MetadataParser] 가 담당하고,
 * 이 클래스는 순수 Map 입력만 다뤄 Android 의존성 없이 단위 테스트가 가능하다.
 * (구버전 스키마 → 2.1 필드 승격 규칙을 여기서 검증한다.)
 */
object MetadataMapper {

    const val CURRENT_SCHEMA = "2.1"

    /**
     * 평범한 Map(JSON을 파싱한 결과)을 PostMetadata 로 변환한다.
     * 구버전(schema 1.x 또는 schema_version 누락)도 안전하게 승격한다.
     */
    fun fromMap(map: Map<String, Any?>): PostMetadata {
        val schema = str(map["schema_version"]) ?: legacySchemaGuess(map)

        // 태그: 배열 또는 문자열(공백/쉼표 구분) 모두 허용
        val tags = when (val t = map["tags"] ?: map["naver_tags"]) {
            is List<*> -> t.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
            is String -> t.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }

        // 게시판: 2.1=naver_category. 구버전 별칭 category/board 도 인식.
        val category = str(map["naver_category"]) ?: str(map["category"]) ?: str(map["board"])

        // 이미지 범위: 2.1 필드. 없으면 image_count/기본값에서 유도.
        val imageCount = int(map["image_count"])
        val imageMin = int(map["image_min"])
        val imageRec = int(map["image_recommended"]) ?: int(map["recommended_image_count"])
        val imageMax = int(map["image_max"])

        return PostMetadata(
            schemaVersion = if (schema.isNullOrBlank()) CURRENT_SCHEMA else schema,
            postId = str(map["post_id"]) ?: str(map["id"]),
            title = str(map["title"]),
            naverCategory = category,
            tags = tags,
            imageCount = imageCount,
            imageMin = imageMin,
            imageRecommended = imageRec,
            imageMax = imageMax,
            contentVersion = int(map["content_version"]) ?: 1,
            topicKey = str(map["topic_key"]) ?: str(map["keyword"]),
            topicAngle = str(map["topic_angle"]) ?: str(map["angle"]),
            instructionVersion = str(map["instruction_version"])
        )
    }

    /** schema_version 이 없을 때 구버전 여부를 추정한다. */
    private fun legacySchemaGuess(map: Map<String, Any?>): String? {
        // 2.1 전용 키가 하나도 없고 구형 키만 있으면 1.0 으로 간주(승격 대상)
        val has21 = map.keys.any { it in setOf("naver_category", "image_min", "image_max", "topic_key", "topic_angle") }
        return if (has21) CURRENT_SCHEMA else "1.0"
    }

    private fun str(v: Any?): String? = v?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun int(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }
}
