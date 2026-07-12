package com.gptgongjakso.naverwriterhelper.instruction

import android.content.Context

/**
 * 블로그 작업 지시문 관리 (지시서 22). v1.0.0 신규 · 기초 구현.
 *
 * 사용자가 GPT 에 넘길 "블로그 작성 지시문"의 현재 버전 텍스트를 앱에 보관/조회한다.
 * (지시문 자체는 앱 밖 GPT 워크플로우에서 사용되며, 여기서는 버전/본문 보관과
 *  metadata.instruction_version 과의 대조를 돕는 역할을 한다.)
 *
 * 저장: SharedPreferences (백업 제외 대상). 외부 전송 없음.
 */
object InstructionManager {

    private const val PREF = "instruction_store"
    private const val KEY_VERSION = "version"
    private const val KEY_TEXT = "text"
    private const val DEFAULT_VERSION = "1.0.0"

    fun currentVersion(context: Context): String =
        prefs(context).getString(KEY_VERSION, DEFAULT_VERSION) ?: DEFAULT_VERSION

    fun currentText(context: Context): String =
        prefs(context).getString(KEY_TEXT, "") ?: ""

    fun save(context: Context, version: String, text: String) {
        prefs(context).edit()
            .putString(KEY_VERSION, version.trim().ifEmpty { DEFAULT_VERSION })
            .putString(KEY_TEXT, text)
            .apply()
    }

    /**
     * 자료의 instruction_version 이 현재 보관 버전과 일치하는지 확인(주의 표시용).
     * null/빈 값이면 검사하지 않고 true.
     */
    fun matches(context: Context, dataInstructionVersion: String?): Boolean {
        if (dataInstructionVersion.isNullOrBlank()) return true
        return dataInstructionVersion.trim() == currentVersion(context)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
