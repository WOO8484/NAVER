package com.gptgongjakso.naverwriterhelper.diagnostics

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.gptgongjakso.naverwriterhelper.store.AutomationLogStore
import com.gptgongjakso.naverwriterhelper.store.SessionRepository
import com.gptgongjakso.naverwriterhelper.store.db.HistoryStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 진단/이력 내보내기 (지시서 24). v1.0.0 신규 · 기초 구현.
 *
 * 현재 세션 상태머신 스냅샷 + 최근 로그 + 이력 요약을 JSON/CSV 로 만들어
 * 다운로드 폴더(Download/GPT공작소진단)에 저장한다. 원문 본문/태그 내용은 포함하지 않고
 * 개수/해시 앞자리 등 요약만 담아 개인정보 노출을 줄인다.
 */
object DiagnosticsExporter {

    private const val ALBUM = "GPT공작소진단"

    /** JSON 진단을 저장하고 표시용 파일명을 반환. 실패 시 null. */
    fun exportJson(context: Context): String? {
        val json = buildJson(context).toString(2)
        val name = "diagnostics_${stamp()}.json"
        return if (writeToDownloads(context, name, "application/json", json)) name else null
    }

    /** CSV 로그를 저장하고 표시용 파일명을 반환. 실패 시 null. */
    fun exportLogCsv(context: Context): String? {
        val sb = StringBuilder("time_index,line\n")
        AutomationLogStore.all().forEachIndexed { i, line ->
            sb.append(i).append(',').append('"').append(line.replace("\"", "\"\"")).append('"').append('\n')
        }
        val name = "log_${stamp()}.csv"
        return if (writeToDownloads(context, name, "text/csv", sb.toString())) name else null
    }

    private fun buildJson(context: Context): JSONObject {
        val root = JSONObject()
        root.put("app", "GPT 공작소")
        root.put("version", "1.0.0")
        root.put("exported_at", stamp())

        // 상태머신 스냅샷
        val sm = SessionRepository.pipeline
        val stateArr = JSONArray()
        sm.snapshot().forEach { r ->
            stateArr.put(
                JSONObject()
                    .put("state", r.state.name)
                    .put("entered_at", r.enteredAt)
                    .put("retries", r.retries)
                    .put("completed", r.completed)
                    .put("fail_reason", r.failReason ?: "")
            )
        }
        root.put("pipeline_current", sm.current.name)
        root.put("pipeline_history", stateArr)

        // 자료 요약(내용 미포함)
        val data = SessionRepository.postData
        if (data != null) {
            root.put(
                "data_summary", JSONObject()
                    .put("title_length", data.titleLength)
                    .put("body_length", data.bodyLength)
                    .put("tag_count", data.tagCount)
                    .put("image_count", data.imageCount)
                    .put("zip_sha256_prefix", data.zipSha256.take(12))
                    .put("board", SessionRepository.selectedBoard?.key ?: "unmatched")
                    .put("duplicate_verdict", SessionRepository.lastDuplicateResult?.verdict?.name ?: "NONE")
            )
        }

        // 이력 요약(개수만)
        root.put("history_count", runCatching { HistoryStore(context).count() }.getOrDefault(-1))

        // 최근 로그
        val logArr = JSONArray()
        AutomationLogStore.recent(20).forEach { logArr.put(it) }
        root.put("recent_logs", logArr)

        return root
    }

    private fun writeToDownloads(context: Context, name: String, mime: String, content: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    ?: return false
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                // API 30(minSdk) 이상만 지원하므로 이 분기는 실질적으로 사용되지 않음.
                false
            }
        }.getOrDefault(false)
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
}
