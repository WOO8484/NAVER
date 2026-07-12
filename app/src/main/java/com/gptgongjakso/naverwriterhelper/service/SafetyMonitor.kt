package com.gptgongjakso.naverwriterhelper.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.gptgongjakso.naverwriterhelper.helper.PermissionGuideHelper
import com.gptgongjakso.naverwriterhelper.store.AutomationLogStore
import com.gptgongjakso.naverwriterhelper.store.SessionRepository

/**
 * 안전 감시 (지시서 12, v1.1.0에서 10.1 감지 범위 확장). v1.0.0 신규 · v1.1.0 확장.
 *
 * 자동 입력 중 아래 상황에서 오케스트레이터를 일시정지한다.
 *  - 화면 꺼짐(ACTION_SCREEN_OFF) : 이 모듈이 브로드캐스트로 감지 → 오케스트레이터 pauseNow.
 *  - 앱 전환 / 전화 수신(다른 앱이 전면으로) :
 *    각 [com.gptgongjakso.naverwriterhelper.automation.AutomationStepExecutor] 단계 자체가
 *    현재 화면이 허용된 패키지인지 확인하고, 아니면 NeedsUser/Retryable 을 돌려주므로
 *    별도 권한(READ_PHONE_STATE 등) 없이도 다음 틱에서 자연히 멈춘다.
 *  - 접근성/오버레이 권한 해제: [checkPermissionsStillGranted] 를 주기적으로(상태 갱신 시) 호출해
 *    실행 중 권한이 꺼졌으면 즉시 일시정지한다.
 *
 * 권한 최소화 원칙에 따라 통화 상태 리스너(READ_PHONE_STATE)는 사용하지 않는다.
 */
class SafetyMonitor(private val context: Context) {

    private var registered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                onScreenOff()
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        runCatching { context.registerReceiver(screenReceiver, filter) }
            .onSuccess { registered = true }
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(screenReceiver) }
        registered = false
    }

    private fun onScreenOff() {
        pauseIfRunning("화면 꺼짐")
    }

    /**
     * 접근성/오버레이 권한이 자동화 도중 꺼졌는지 확인한다.
     * FloatingControlService 의 상태 갱신 루프 등에서 주기적으로 호출한다(작업지시서 10.1).
     */
    fun checkPermissionsStillGranted() {
        val orchestrator = SessionRepository.orchestrator ?: return
        if (!orchestrator.running) return
        if (!PermissionGuideHelper.isAccessibilityEnabled(context)) {
            pauseIfRunning("접근성 권한이 꺼졌습니다")
            return
        }
        if (!PermissionGuideHelper.canDrawOverlays(context)) {
            pauseIfRunning("오버레이 권한이 꺼졌습니다")
        }
    }

    private fun pauseIfRunning(reason: String) {
        val orchestrator = SessionRepository.orchestrator
        if (orchestrator != null && orchestrator.running) {
            orchestrator.pauseNow(reason)
            AutomationLogStore.add("자동 실행 일시정지 · $reason")
        } else {
            val sm = SessionRepository.pipeline
            if (!sm.current.isHalting) {
                sm.pause(reason)
                AutomationLogStore.add("자동 진행을 일시정지했습니다 · $reason")
            }
        }
        SessionRepository.notifyChanged()
    }
}
