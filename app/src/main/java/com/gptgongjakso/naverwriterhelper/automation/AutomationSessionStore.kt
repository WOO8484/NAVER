package com.gptgongjakso.naverwriterhelper.automation

import android.content.Context
import com.gptgongjakso.naverwriterhelper.image.SavedImageEntry
import com.gptgongjakso.naverwriterhelper.image.SavedImageManifest
import com.gptgongjakso.naverwriterhelper.model.PipelineState
import com.gptgongjakso.naverwriterhelper.statemachine.PipelineStateMachine
import org.json.JSONObject

/**
 * 자동화 세션 영구 저장 (작업지시서 5, 10.3). v1.1.0 신규.
 *
 * SharedPreferences 를 원자적 저장소로 사용해, 앱 프로세스가 재생성되어도
 * 진행 상태를 안전하게 이어하기 할 수 있게 한다. 저장 항목은 지시서 10.3 목록을 따르며,
 * 본문/제목 원문·계정 정보는 절대 저장하지 않는다(자료 원문은 ZIP 재파싱으로만 복원).
 *
 * 단일 자동화 세션 잠금(작업지시서 4.1, 14.1-24)도 이 객체가 관리한다.
 */
object AutomationSessionStore {

    private const val PREF = "automation_session"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_ZIP_SHA = "zip_sha256"
    private const val KEY_TEST_MODE = "test_mode"
    private const val KEY_STATE_SNAPSHOT = "state_snapshot_json"
    private const val KEY_IMAGE_MANIFEST = "image_manifest_json"
    private const val KEY_SELECTED_BOARD = "selected_board_key"
    private const val KEY_UPDATED_AT = "updated_at"

    data class Snapshot(
        val sessionId: String,
        val zipSha256: String,
        val testMode: Boolean,
        val stateMap: Map<String, String>,
        val imageManifest: List<SavedImageEntry>,
        val selectedBoardKey: String?,
        val updatedAtMillis: Long
    )

    /** 현재 세션 상태를 원자적으로 저장한다. */
    fun save(
        context: Context,
        sessionId: String,
        zipSha256: String,
        testMode: Boolean,
        stateMachine: PipelineStateMachine,
        imageManifest: List<SavedImageEntry>,
        selectedBoardKey: String?,
        clock: () -> Long = { System.currentTimeMillis() }
    ) {
        val stateObj = JSONObject()
        stateMachine.toPersistableMap().forEach { (k, v) -> stateObj.put(k, v) }

        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_ZIP_SHA, zipSha256)
            .putBoolean(KEY_TEST_MODE, testMode)
            .putString(KEY_STATE_SNAPSHOT, stateObj.toString())
            .putString(KEY_IMAGE_MANIFEST, SavedImageManifest.toJson(imageManifest))
            .putString(KEY_SELECTED_BOARD, selectedBoardKey ?: "")
            .putLong(KEY_UPDATED_AT, clock())
            .apply()
    }

    fun load(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val zipSha = prefs.getString(KEY_ZIP_SHA, "") ?: ""
        val testMode = prefs.getBoolean(KEY_TEST_MODE, false)
        val stateJson = prefs.getString(KEY_STATE_SNAPSHOT, null) ?: return null
        val obj = runCatching { JSONObject(stateJson) }.getOrNull() ?: return null
        val map = HashMap<String, String>()
        obj.keys().forEach { k -> map[k] = obj.optString(k) }
        val manifest = SavedImageManifest.fromJson(prefs.getString(KEY_IMAGE_MANIFEST, "[]") ?: "[]")
        val boardKey = prefs.getString(KEY_SELECTED_BOARD, "")?.takeIf { it.isNotBlank() }
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        return Snapshot(sessionId, zipSha, testMode, map, manifest, boardKey, updatedAt)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 앱 재실행 시 "중단된 작업이 있습니다" 안내를 띄울지 판단(작업지시서 10.3). */
    fun hasInterruptedSession(context: Context): Boolean {
        val snap = load(context) ?: return false
        val state = snap.stateMap["current"] ?: return false
        return isInterruptedState(state)
    }

    /** 순수 판정: 이어하기 대상이 되는 상태 문자열인지(단위 테스트 가능). */
    fun isInterruptedState(currentStateName: String): Boolean =
        currentStateName.isNotBlank() &&
            currentStateName != PipelineState.COMPLETED_BY_USER.name &&
            currentStateName != PipelineState.CANCELLED.name &&
            currentStateName != PipelineState.RECEIVED.name

    // ======================= 단일 세션 잠금 (작업지시서 4.1 / 14.1-24) =======================
    // 접근성 서비스·플로팅 서비스·MainActivity 는 모두 앱 기본 프로세스에서 실행되므로
    // 프로세스 내 메모리 잠금으로 "동시에 자동화 세션 1개만" 을 보장한다.

    @Volatile
    private var sessionRunning = false

    /** 잠금 획득 시도. 이미 실행 중이면 false. */
    fun tryAcquireLock(): Boolean = synchronized(this) {
        if (sessionRunning) false else { sessionRunning = true; true }
    }

    fun releaseLock() {
        synchronized(this) { sessionRunning = false }
    }

    fun isLocked(): Boolean = sessionRunning
}
