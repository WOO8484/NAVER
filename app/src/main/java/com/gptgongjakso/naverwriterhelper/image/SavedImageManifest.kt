package com.gptgongjakso.naverwriterhelper.image

import com.gptgongjakso.naverwriterhelper.model.ImageRole
import org.json.JSONArray
import org.json.JSONObject

/**
 * 세션별로 저장된 이미지의 관리 정보 (작업지시서 8.2). v1.1.0 신규.
 * 이미지 내용은 담지 않고 추적에 필요한 메타데이터만 JSON으로 직렬화한다.
 */
data class SavedImageEntry(
    val sessionId: String,
    val postId: String,
    val originalName: String,
    val savedName: String,
    val mediaStoreUri: String,
    val role: ImageRole,
    val orderIndex: Int,
    val savedAtMillis: Long,
    val sha256: String
)

object SavedImageManifest {

    fun toJson(entries: List<SavedImageEntry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("session_id", e.sessionId)
                    .put("post_id", e.postId)
                    .put("original_name", e.originalName)
                    .put("saved_name", e.savedName)
                    .put("media_store_uri", e.mediaStoreUri)
                    .put("role", e.role.name)
                    .put("order_index", e.orderIndex)
                    .put("saved_at", e.savedAtMillis)
                    .put("sha256", e.sha256)
            )
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<SavedImageEntry> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<SavedImageEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = runCatching { ImageRole.valueOf(o.optString("role")) }.getOrDefault(ImageRole.BODY)
            out.add(
                SavedImageEntry(
                    sessionId = o.optString("session_id"),
                    postId = o.optString("post_id"),
                    originalName = o.optString("original_name"),
                    savedName = o.optString("saved_name"),
                    mediaStoreUri = o.optString("media_store_uri"),
                    role = role,
                    orderIndex = o.optInt("order_index"),
                    savedAtMillis = o.optLong("saved_at"),
                    sha256 = o.optString("sha256")
                )
            )
        }
        return out
    }

    /**
     * 세션별 고유 저장 파일명 생성(작업지시서 8.2 예시 형식):
     * GPTG_<postId>_<sessionId>_<NN>_<role>.<ext>
     */
    fun buildSaveName(postId: String, sessionId: String, orderIndex: Int, role: ImageRole, extension: String): String {
        val roleTag = if (role == ImageRole.THUMBNAIL) "thumbnail" else "body"
        val idx = orderIndex.coerceAtLeast(0).toString().padStart(2, '0')
        val safePostId = sanitize(postId).take(24).ifBlank { "unknown" }
        val safeSessionId = sanitize(sessionId).take(12).ifBlank { "s" }
        val ext = extension.lowercase().removePrefix(".").ifBlank { "jpg" }
        return "GPTG_${safePostId}_${safeSessionId}_${idx}_${roleTag}.$ext"
    }

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "")
}
