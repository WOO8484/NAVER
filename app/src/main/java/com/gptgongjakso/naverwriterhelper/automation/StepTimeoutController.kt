package com.gptgongjakso.naverwriterhelper.automation

import android.os.Handler
import android.os.Looper

/**
 * 단계별 타임아웃 관리 (작업지시서 5, 6.4, 10.4). v1.1.0 신규.
 *
 * 즉시 중지 시 예약된 Handler 콜백을 모두 취소할 수 있도록 단일 토큰만 유지한다
 * (한 번에 하나의 자동화 세션만 실행되므로 타임아웃도 항상 1개만 존재한다).
 */
class StepTimeoutController(
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    private var currentToken: Runnable? = null

    /** durationMs 후 onTimeout 을 실행할 타임아웃을 건다. 이전 타임아웃은 자동 취소된다. */
    fun arm(durationMs: Long, onTimeout: () -> Unit) {
        cancel()
        val r = Runnable { currentToken = null; onTimeout() }
        currentToken = r
        handler.postDelayed(r, durationMs)
    }

    /** 정상 완료/재시도/일시정지/즉시중지 시 타임아웃을 해제한다. */
    fun cancel() {
        currentToken?.let { handler.removeCallbacks(it) }
        currentToken = null
    }

    fun isArmed(): Boolean = currentToken != null
}
