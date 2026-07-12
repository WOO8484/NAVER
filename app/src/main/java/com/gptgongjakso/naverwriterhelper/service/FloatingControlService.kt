package com.gptgongjakso.naverwriterhelper.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.gptgongjakso.naverwriterhelper.MainActivity
import com.gptgongjakso.naverwriterhelper.R
import com.gptgongjakso.naverwriterhelper.helper.ClipboardInputHelper
import com.gptgongjakso.naverwriterhelper.model.PipelineState
import com.gptgongjakso.naverwriterhelper.store.AutomationLogStore
import com.gptgongjakso.naverwriterhelper.store.SessionRepository

/**
 * 네이버 화면 위에 뜨는 작은 플로팅 컨트롤. v0.1.1 이식 + v1.0.0/v1.1.0 확장.
 *
 * v1.1.0 확장(작업지시서 4.2/4.3):
 *  - 일반 모드 주 조작을 일시정지/이어하기/즉시중지로 단순화(AutomationOrchestrator 구독).
 *  - 개별 입력 버튼(제목/본문/태그/사진)은 기본 접힌 "수동 복구" 영역으로 이동.
 *    기존 함수(NaverAccessibilityService.inputTitle 등)를 그대로 재사용한다(중복 구현 없음).
 *  - 상태 표시에 현재 단계·진행률·태그/사진 카운트·재시도 횟수·시험 모드·실패 원인을 포함한다.
 *  - SafetyMonitor.checkPermissionsStillGranted() 를 주기적으로 호출해 실행 중 권한 해제를 감지한다.
 */
class FloatingControlService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_control"
        private const val NOTIF_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var statusText: TextView? = null
    private var safetyMonitor: SafetyMonitor? = null
    private var recoveryExpanded = false

    private val sessionListener: () -> Unit = { updateStatus() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
        addOverlay()
        SessionRepository.addListener(sessionListener)
        safetyMonitor = SafetyMonitor(this).also { it.start() }
        AutomationLogStore.add("플로팅 컨트롤 시작")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        SessionRepository.removeListener(sessionListener)
        safetyMonitor?.stop()
        safetyMonitor = null
        removeOverlay()
        // 서비스 종료 시 앱이 복사한 클립보드만 조건부 정리
        ClipboardInputHelper.clearIfOwn(this)
        AutomationLogStore.add("플로팅 컨트롤 종료")
        super.onDestroy()
    }

    // ---------- 포그라운드 알림 ----------
    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "플로팅 컨트롤",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "자동 실행 상태 플로팅 컨트롤 실행 중" }
            nm.createNotificationChannel(channel)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPT 공작소")
            .setContentText("자동 실행 중 · 발행/임시저장은 직접 눌러야 합니다")
            .setSmallIcon(R.drawable.ic_stat_helper)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    // ---------- 오버레이 ----------
    private fun addOverlay() {
        if (overlayView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.view_floating_control, null)
        overlayView = view
        statusText = view.findViewById(R.id.floatingStatus)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 200
        }

        // 드래그 이동
        val handle = view.findViewById<View>(R.id.floatingHandle)
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                        return true
                    }
                }
                return false
            }
        })

        wireButtons(view)

        runCatching { windowManager.addView(view, params) }
            .onFailure {
                AutomationLogStore.add("플로팅 표시 실패 · 오버레이 권한을 확인하세요")
                stopSelf()
            }
        updateStatus()
    }

    private fun removeOverlay() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        statusText = null
    }

    // ---------- 버튼 동작 ----------
    private fun wireButtons(view: View) {
        // 일반 모드 주 조작(작업지시서 4.2)
        view.findViewById<View>(R.id.btnAutoPause).setOnClickListener { onAutoPause() }
        view.findViewById<View>(R.id.btnAutoResume).setOnClickListener { onAutoResume() }
        view.findViewById<View>(R.id.btnAutoCancel).setOnClickListener { onAutoCancel() }

        // 수동 복구 펼치기/접기(기본 접힘)
        val toggle = view.findViewById<TextView>(R.id.btnToggleRecovery)
        val panel = view.findViewById<View>(R.id.recoveryPanel)
        toggle.setOnClickListener {
            recoveryExpanded = !recoveryExpanded
            panel.visibility = if (recoveryExpanded) View.VISIBLE else View.GONE
            toggle.text = if (recoveryExpanded) "▴ 수동 복구 접기" else "▾ 수동 복구 펼치기"
        }

        // 수동 복구 개별 버튼(v1.0.1 기존 함수 재사용 — 중복 구현 없음)
        view.findViewById<View>(R.id.btnTitle).setOnClickListener { onTitle() }
        view.findViewById<View>(R.id.btnBody).setOnClickListener { onBody() }
        view.findViewById<View>(R.id.btnTagsAuto).setOnClickListener { onTagsAuto() }
        view.findViewById<View>(R.id.btnTagNext).setOnClickListener { onTagNext() }
        view.findViewById<View>(R.id.btnTagAgain).setOnClickListener { onTagAgain() }
        view.findViewById<View>(R.id.btnPhoto).setOnClickListener { onPhoto() }
        view.findViewById<View>(R.id.btnClose).setOnClickListener { stopSelf() }
    }

    private fun requireData(): Boolean {
        if (!SessionRepository.hasData()) {
            toast("먼저 앱에서 자료를 불러오세요")
            return false
        }
        return true
    }

    // ---------- 전체 자동 실행 주 조작 ----------
    private fun onAutoPause() {
        val orchestrator = SessionRepository.orchestrator
        if (orchestrator == null || !orchestrator.running) {
            toast("실행 중인 자동화가 없습니다")
            return
        }
        orchestrator.pauseNow("사용자 일시정지")
        toast("일시정지했습니다")
    }

    private fun onAutoResume() {
        val orchestrator = SessionRepository.orchestrator
        if (orchestrator == null) {
            toast("먼저 메인 화면에서 전체 자동 실행을 시작하세요")
            return
        }
        if (SessionRepository.pipeline.current != PipelineState.PAUSED) {
            toast("일시정지 상태가 아닙니다")
            return
        }
        orchestrator.resumeNow()
        toast("이어서 진행합니다")
    }

    private fun onAutoCancel() {
        val orchestrator = SessionRepository.orchestrator
        if (orchestrator == null) {
            toast("실행 중인 자동화가 없습니다")
            return
        }
        orchestrator.cancelNow()
        toast("즉시 중지했습니다")
    }

    /** 상태머신을 특정 단계로 이동(발행 유도 아님, 단계 표시/추적용 — 수동 복구 버튼 전용). */
    private fun moveState(target: PipelineState) {
        val sm = SessionRepository.pipeline
        if (!sm.current.isTerminal && sm.current != PipelineState.PAUSED) sm.transitionTo(target)
        SessionRepository.notifyChanged()
    }

    private fun onTitle() {
        if (!requireData()) return
        moveState(PipelineState.INPUTTING_TITLE)
        val svc = NaverAccessibilityService.instance
        if (svc != null) {
            svc.inputTitle(this)
        } else {
            ClipboardInputHelper.copyWithAutoClear(this, "제목", SessionRepository.postData!!.title, 120_000L)
            AutomationLogStore.add("제목 클립보드 복사(접근성 꺼짐) · 입력칸에 붙여넣기")
            toast("제목을 클립보드에 복사했습니다")
        }
        updateStatus()
    }

    private fun onBody() {
        if (!requireData()) return
        moveState(PipelineState.INPUTTING_BODY)
        val svc = NaverAccessibilityService.instance
        if (svc != null) {
            svc.inputBody(this)
        } else {
            ClipboardInputHelper.copyWithAutoClear(this, "본문", SessionRepository.postData!!.body, 120_000L)
            AutomationLogStore.add("본문 클립보드 복사(접근성 꺼짐) · 입력칸 길게 눌러 붙여넣기")
            toast("본문을 클립보드에 복사했습니다")
        }
        updateStatus()
    }

    private fun onTagsAuto() {
        if (!requireData()) return
        moveState(PipelineState.INPUTTING_TAGS)
        val svc = NaverAccessibilityService.instance
        if (svc != null) {
            toast("태그를 1개씩 입력합니다")
            svc.autoInputAllTags(
                context = this,
                onEach = { updateStatus() },
                onFinish = { updateStatus() }
            )
        } else {
            onTagNext()
            toast("접근성이 꺼져 있어 태그를 1개씩 복사합니다")
        }
        updateStatus()
    }

    private fun onTagNext() {
        if (!requireData()) return
        val svc = NaverAccessibilityService.instance
        if (svc != null) {
            svc.copyNextTag(this)
        } else {
            val c = SessionRepository.tagController
            val tag = c.current()
            if (tag != null) {
                ClipboardInputHelper.copyWithAutoClear(this, "태그", tag, 120_000L)
                c.markCurrentDone()
                AutomationLogStore.add("다음 태그 복사(${c.progressText()})")
                SessionRepository.notifyChanged()
            } else {
                toast("복사할 다음 태그가 없습니다")
            }
        }
        updateStatus()
    }

    private fun onTagAgain() {
        if (!requireData()) return
        NaverAccessibilityService.instance?.copyCurrentTagAgain(this) ?: run {
            val c = SessionRepository.tagController
            val idx = (c.cursor - 1).coerceAtLeast(0)
            val tag = c.snapshot().getOrNull(idx)
            if (tag != null) {
                ClipboardInputHelper.copyWithAutoClear(this, "태그", tag, 120_000L)
                AutomationLogStore.add("현재 태그 다시 복사")
            }
        }
        toast("현재 태그를 다시 복사했습니다")
    }

    private fun onPhoto() {
        if (!requireData()) return
        moveState(PipelineState.OPENING_PHOTO_PICKER)
        val svc = NaverAccessibilityService.instance
        if (svc != null) {
            svc.openPhotoAttach(this)
        } else {
            AutomationLogStore.add("사진 첨부 안내 · 네이버 사진 버튼을 눌러 GPT공작소 앨범에서 선택")
            toast("네이버 사진 버튼을 눌러 GPT공작소 앨범을 선택하세요")
        }
        updateStatus()
    }

    // ---------- 상태 표시 (작업지시서 4.3 필수 표시 항목) ----------
    private fun updateStatus() {
        safetyMonitor?.checkPermissionsStillGranted()
        val tv = statusText ?: return
        val data = SessionRepository.postData
        val accOn = NaverAccessibilityService.isRunning()
        val accBadge = if (accOn) "접근성 ON" else "접근성 OFF(복사모드)"
        val text = if (data == null) {
            "자료 없음 · $accBadge"
        } else {
            val sm = SessionRepository.pipeline
            val orchestrator = SessionRepository.orchestrator
            val runningBadge = if (orchestrator?.running == true) "자동 실행 중" else if (sm.current == PipelineState.PAUSED) "일시정지" else "대기"
            val retries = sm.retriesOf(sm.current)
            val testBadge = if (SessionRepository.testMode) " · [시험 모드]" else ""
            val failReason = sm.pauseReason?.let { " · 사유: $it" } ?: ""
            "[$runningBadge] ${sm.statusSummary()}$testBadge\n" +
                "태그 ${SessionRepository.tagController.progressText()} · 사진 ${sm.selectedPhotoCount}/${sm.totalPhotoCount} · 재시도 ${retries}회$failReason\n" +
                accBadge
        }
        tv.text = text
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
