package com.gptgongjakso.naverwriterhelper.helper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 클립보드 복사 + "조건부" 자동 정리 도우미.
 *
 * 자동 입력 실패 시의 fallback(클립보드 복사)은 그대로 유지한다.
 * 다만 앱이 복사한 값이 "현재 클립보드 값과 동일할 때만" 지운다.
 *  - 사용자가 이후 다른 내용을 복사했으면 절대 지우지 않는다.
 *  - 현재 클립보드를 읽지 못하면(백그라운드 제한 등) 안전을 위해 지우지 않는다.
 *  - 복사 직후 즉시 삭제하지 않는다(사용자가 붙여넣을 시간 보장).
 */
object ClipboardInputHelper {

    @Volatile
    private var lastCopiedByApp: String? = null

    private val handler = Handler(Looper.getMainLooper())

    private fun cm(context: Context): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** 복사(앱이 복사한 값으로 기억). 즉시 삭제하지 않는다. */
    fun copy(context: Context, label: String, text: String) {
        cm(context).setPrimaryClip(ClipData.newPlainText(label, text))
        lastCopiedByApp = text
    }

    /** 복사 후 delayMs 뒤에 "그 값이 그대로면" 조건부 삭제 예약. */
    fun copyWithAutoClear(context: Context, label: String, text: String, delayMs: Long) {
        copy(context, label, text)
        scheduleClear(context, text, delayMs)
    }

    /** 특정 값에 대해 delayMs 뒤 조건부 삭제 예약. (그 사이 값이 바뀌면 삭제 안 함) */
    fun scheduleClear(context: Context, expected: String, delayMs: Long) {
        val appCtx = context.applicationContext
        handler.postDelayed({ clearIfMatches(appCtx, expected) }, delayMs)
    }

    /** 앱이 마지막으로 복사한 값이 현재 클립보드와 같으면 즉시 삭제. */
    fun clearIfOwn(context: Context): Boolean {
        val remembered = lastCopiedByApp ?: return false
        return clearIfMatches(context, remembered)
    }

    /** 현재 클립보드가 expected 와 같을 때만 삭제. 읽기 불가/불일치 시 삭제하지 않음. */
    private fun clearIfMatches(context: Context, expected: String): Boolean {
        val current = currentClipText(context) ?: return false // 읽지 못하면 안전하게 보존
        if (current != expected) return false                   // 사용자가 바꿨으면 보존
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm(context).clearPrimaryClip()
            } else {
                cm(context).setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }.isSuccess
        if (ok && lastCopiedByApp == expected) lastCopiedByApp = null
        return ok
    }

    /** 현재 클립보드 텍스트(읽기 제한/실패 시 null). */
    private fun currentClipText(context: Context): String? = runCatching {
        cm(context).primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()
    }.getOrNull()
}
