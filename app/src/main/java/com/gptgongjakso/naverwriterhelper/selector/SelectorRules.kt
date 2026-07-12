package com.gptgongjakso.naverwriterhelper.selector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 네이버 화면 요소 선택자 규칙 (작업지시서 9). v1.1.0 전면 재구현.
 *
 * v1.0.0의 단순 힌트 목록(title_hints 등)에서 화면별 구조화 규칙으로 확장하고,
 * 접근성 탐색([com.gptgongjakso.naverwriterhelper.service.NaverAccessibilityService])이
 * 실제로 이 규칙을 사용해 노드를 찾도록 연결한다(지시서 9.1, 20장 금지사항 6번째 항목 대응).
 *
 * 관리 3단계(지시서 9.1):
 *  1) 정식 적용본  files/selector_rules_active.json
 *  2) 이전 정상본  files/selector_rules_previous.json (정식 적용 직전 자동 백업)
 *  3) 앱 내장 기본본  assets/selector_rules.json (미검증 · 실기 확인 전까지의 최선 추정치)
 *
 * 스키마(schema_version)가 다르거나 파싱에 실패하면 그 파일은 건너뛰고 다음 우선순위로
 * 폴백한다. 인터넷 자동 다운로드는 하지 않는다(파일은 항상 SAF로 사용자가 직접 불러온다).
 */
object SelectorRules {

    private const val SUPPORTED_SCHEMA = "1.1"
    private const val FILE_ACTIVE = "selector_rules_active.json"
    private const val FILE_PREVIOUS = "selector_rules_previous.json"
    private const val FILE_TRIAL = "selector_rules_trial.json"
    private const val ASSET_DEFAULT = "selector_rules.json"

    enum class Source { ACTIVE, PREVIOUS, BUILTIN_DEFAULT, TRIAL }

    /** 선택자 후보 1개(작업지시서 9.2 조건 조합) */
    data class Candidate(
        val viewIdContains: String? = null,
        val textExact: String? = null,
        val textContains: String? = null,
        val descExact: String? = null,
        val descContains: String? = null,
        val hintContains: String? = null,
        val className: String? = null,
        val editableOnly: Boolean = false,
        val clickableOnly: Boolean = false,
        /**
         * 위험(발행/등록/게시/공개/임시저장/예약) 계열과 텍스트가 겹칠 수 있는 안전 확인/완료
         * 버튼에만 설정하는 화면 역할 태그. 이 값이 있으면 클릭 직전 안전 정책이
         * 현재 화면 역할과 이 값이 정확히 일치하는지 재검사한다(지시서 7.3/13).
         */
        val screenRole: String? = null
    )

    data class ScreenRule(
        val signals: List<Candidate> = emptyList(),
        val writeButton: List<Candidate> = emptyList(),
        val categoryButton: List<Candidate> = emptyList(),
        val titleField: List<Candidate> = emptyList(),
        val bodyField: List<Candidate> = emptyList(),
        val photoButton: List<Candidate> = emptyList(),
        val tagButton: List<Candidate> = emptyList(),
        val categoryItems: List<Candidate> = emptyList(),
        val safeConfirmButton: List<Candidate> = emptyList(),
        val packageNames: List<String> = emptyList(),
        val albumButton: List<Candidate> = emptyList(),
        val gptAlbumItem: List<Candidate> = emptyList(),
        val photoGridItems: List<Candidate> = emptyList(),
        val selectedCount: List<Candidate> = emptyList(),
        val safeDoneButton: List<Candidate> = emptyList()
    )

    data class RuleSet(
        val schemaVersion: String,
        val rulesVersion: String,
        val targetPackage: String,
        val screens: Map<String, ScreenRule>,
        val source: Source
    ) {
        fun screen(name: String): ScreenRule = screens[name] ?: ScreenRule()
    }

    sealed class ValidationResult {
        data class Valid(val ruleSet: RuleSet) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    // ======================= 순수 로직 (Context 불필요 · 단위 테스트 가능) =======================

    /** JSON 문자열을 검증하고 파싱한다. 스키마 불일치/필드 오류를 모두 여기서 걸러낸다. */
    fun validate(json: String, source: Source): ValidationResult {
        val obj = runCatching { JSONObject(json) }.getOrNull()
            ?: return ValidationResult.Invalid("JSON 파싱 실패")

        val schemaVersion = obj.optString("schema_version", "")
        if (schemaVersion != SUPPORTED_SCHEMA) {
            return ValidationResult.Invalid("지원하지 않는 schema_version: '$schemaVersion' (필요: $SUPPORTED_SCHEMA)")
        }
        val rulesVersion = obj.optString("rules_version", "unknown")
        val targetPkg = obj.optJSONObject("target_app")?.optString("package", "") ?: ""
        if (targetPkg.isBlank()) {
            return ValidationResult.Invalid("target_app.package 누락")
        }
        val screensObj = obj.optJSONObject("screens")
            ?: return ValidationResult.Invalid("screens 누락")

        val screens = HashMap<String, ScreenRule>()
        for (key in screensObj.keys()) {
            val s = screensObj.optJSONObject(key) ?: continue
            screens[key] = ScreenRule(
                signals = candidateList(s, "signals"),
                writeButton = candidateList(s, "write_button"),
                categoryButton = candidateList(s, "category_button"),
                titleField = candidateList(s, "title_field"),
                bodyField = candidateList(s, "body_field"),
                photoButton = candidateList(s, "photo_button"),
                tagButton = candidateList(s, "tag_button"),
                categoryItems = candidateList(s, "category_items"),
                safeConfirmButton = candidateList(s, "safe_confirm_button"),
                packageNames = strList(s, "package_names"),
                albumButton = candidateList(s, "album_button"),
                gptAlbumItem = candidateList(s, "gpt_album_item"),
                photoGridItems = candidateList(s, "photo_grid_items"),
                selectedCount = candidateList(s, "selected_count"),
                safeDoneButton = candidateList(s, "safe_done_button")
            )
        }

        return ValidationResult.Valid(
            RuleSet(
                schemaVersion = schemaVersion,
                rulesVersion = rulesVersion,
                targetPackage = targetPkg,
                screens = screens,
                source = source
            )
        )
    }

    private fun candidateList(screenObj: JSONObject, key: String): List<Candidate> {
        val arr = screenObj.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            out.add(
                Candidate(
                    viewIdContains = c.optString("view_id_contains").ifBlank { null },
                    textExact = c.optString("text_exact").ifBlank { null },
                    textContains = c.optString("text_contains").ifBlank { null },
                    descExact = c.optString("desc_exact").ifBlank { null },
                    descContains = c.optString("desc_contains").ifBlank { null },
                    hintContains = c.optString("hint_contains").ifBlank { null },
                    className = c.optString("class_name").ifBlank { null },
                    editableOnly = c.optBoolean("editable_only", false),
                    clickableOnly = c.optBoolean("clickable_only", false),
                    screenRole = c.optString("screen_role").ifBlank { null }
                )
            )
        }
        return out
    }

    private fun strList(obj: JSONObject, key: String): List<String> {
        val arr: JSONArray = obj.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) obj.optJSONArray(key)?.optString(i)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    private fun String?.ifBlank(default: () -> String?): String? = if (this.isNullOrBlank()) default() else this

    // ======================= Context 필요 (파일 I/O) =======================

    /** 우선순위대로 로드: 정식 적용본 → 이전 정상본 → 앱 내장 기본본. */
    fun loadActive(context: Context): RuleSet {
        readFile(context, FILE_ACTIVE)?.let { json ->
            (validate(json, Source.ACTIVE) as? ValidationResult.Valid)?.let { return it.ruleSet }
        }
        readFile(context, FILE_PREVIOUS)?.let { json ->
            (validate(json, Source.PREVIOUS) as? ValidationResult.Valid)?.let { return it.ruleSet }
        }
        val assetJson = runCatching {
            context.assets.open(ASSET_DEFAULT).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (assetJson != null) {
            (validate(assetJson, Source.BUILTIN_DEFAULT) as? ValidationResult.Valid)?.let { return it.ruleSet }
        }
        // 자산조차 없거나 손상된 경우의 최후 폴백(빈 규칙 — 접근성 서비스는 이 경우 안전하게 아무 것도
        // 클릭하지 않고 PAUSED 로 넘어간다).
        return RuleSet(SUPPORTED_SCHEMA, "empty-fallback", "com.nhn.android.blog", emptyMap(), Source.BUILTIN_DEFAULT)
    }

    /** 시험 적용: 스키마만 검증하고 정식 적용본은 건드리지 않는다. */
    fun applyTrial(context: Context, json: String): ValidationResult {
        val result = validate(json, Source.TRIAL)
        if (result is ValidationResult.Valid) writeFile(context, FILE_TRIAL, json)
        return result
    }

    /** 정식 적용: 검증 통과 시 현재 정식본을 이전 정상본으로 백업한 뒤 교체한다. */
    fun applyOfficial(context: Context, json: String): ValidationResult {
        val result = validate(json, Source.ACTIVE)
        if (result is ValidationResult.Valid) {
            readFile(context, FILE_ACTIVE)?.let { writeFile(context, FILE_PREVIOUS, it) }
            writeFile(context, FILE_ACTIVE, json)
        }
        return result
    }

    /** 이전 정상본 복원(정식 적용본 삭제 → 다음 로드부터 이전본 사용). */
    fun restorePrevious(context: Context): Boolean {
        val prev = readFile(context, FILE_PREVIOUS) ?: return false
        writeFile(context, FILE_ACTIVE, prev)
        return true
    }

    /** 앱 내장 기본본으로 복원(정식/이전 적용본을 모두 제거). */
    fun restoreBuiltinDefault(context: Context): Boolean {
        deleteFile(context, FILE_ACTIVE)
        deleteFile(context, FILE_PREVIOUS)
        return true
    }

    fun currentVersion(context: Context): String = loadActive(context).rulesVersion

    private fun readFile(context: Context, name: String): String? {
        val f = java.io.File(context.filesDir, name)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    private fun writeFile(context: Context, name: String, content: String) {
        runCatching { java.io.File(context.filesDir, name).writeText(content) }
    }

    private fun deleteFile(context: Context, name: String) {
        runCatching { java.io.File(context.filesDir, name).delete() }
    }
}
