package com.gptgongjakso.naverwriterhelper.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 앱 전역 로그 저장소.
 * - 최근 로그를 메모리에 보관하고 SharedPreferences 에 영속 저장한다.
 * - Activity / FloatingControlService 가 함께 구독한다.
 */
object AutomationLogStore {

    private const val PREF = "automation_log"
    private const val KEY_LINES = "lines"
    private const val MAX_KEEP = 50
    private const val RECENT_SHOWN = 5

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lines = ArrayList<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var appContext: Context? = null

    /** 앱 시작 시 1회 호출 */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // 저장된 로그 복원
        val saved = appContext!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_LINES, null)
        if (!saved.isNullOrEmpty()) {
            synchronized(lines) {
                lines.clear()
                lines.addAll(saved.split("\n").filter { it.isNotBlank() })
            }
        }
    }

    /** 로그 추가 (어느 스레드에서 호출해도 안전) */
    fun add(message: String) {
        val stamped = "[${timeFmt.format(Date())}] $message"
        synchronized(lines) {
            lines.add(stamped)
            while (lines.size > MAX_KEEP) lines.removeAt(0)
            persist()
        }
        notifyChanged()
    }

    /** 최근 로그 n개 (오래된 것 -> 최신 순) */
    fun recent(n: Int = RECENT_SHOWN): List<String> = synchronized(lines) {
        if (lines.size <= n) lines.toList() else lines.subList(lines.size - n, lines.size).toList()
    }

    fun all(): List<String> = synchronized(lines) { lines.toList() }

    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyChanged() {
        mainHandler.post { listeners.forEach { runCatching { it() } } }
    }

    private fun persist() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LINES, lines.joinToString("\n"))
            .apply()
    }
}
