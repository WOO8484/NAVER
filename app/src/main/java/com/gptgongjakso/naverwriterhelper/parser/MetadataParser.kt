package com.gptgongjakso.naverwriterhelper.parser

import com.gptgongjakso.naverwriterhelper.model.PostMetadata
import org.json.JSONArray
import org.json.JSONObject

/**
 * metadata.json 문자열 → PostMetadata 브릿지. v1.0.0 신규.
 *
 * org.json 파싱만 담당하고, 스키마 매핑/구버전 변환의 실제 로직은
 * 순수 클래스 [MetadataMapper] 에 위임한다(단위 테스트 가능성 확보).
 */
object MetadataParser {

    /** JSON 문자열을 PostMetadata 로. 실패/빈 값이면 null. */
    fun fromJsonStringOrNull(text: String): PostMetadata? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return MetadataMapper.fromMap(toMap(obj))
    }

    private fun toMap(obj: JSONObject): Map<String, Any?> {
        val map = HashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = normalize(obj.opt(k))
        }
        return map
    }

    private fun normalize(v: Any?): Any? = when (v) {
        is JSONArray -> (0 until v.length()).map { normalize(v.opt(it)) }
        is JSONObject -> null // 중첩 객체는 현재 스키마에서 사용하지 않음
        JSONObject.NULL -> null
        else -> v
    }
}
