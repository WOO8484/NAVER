package com.gptgongjakso.naverwriterhelper

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gptgongjakso.naverwriterhelper.automation.AutomationOrchestrator
import com.gptgongjakso.naverwriterhelper.automation.AutomationSessionStore
import com.gptgongjakso.naverwriterhelper.automation.AutomationStepExecutorImpl
import com.gptgongjakso.naverwriterhelper.automation.StepTimeoutController
import com.gptgongjakso.naverwriterhelper.databinding.ActivityMainBinding
import com.gptgongjakso.naverwriterhelper.dedup.DuplicateChecker
import com.gptgongjakso.naverwriterhelper.helper.ClipboardInputHelper
import com.gptgongjakso.naverwriterhelper.helper.ImageSaveHelper
import com.gptgongjakso.naverwriterhelper.helper.NaverLaunchHelper
import com.gptgongjakso.naverwriterhelper.helper.PermissionGuideHelper
import com.gptgongjakso.naverwriterhelper.image.ImageValidator
import com.gptgongjakso.naverwriterhelper.model.DuplicateVerdict
import com.gptgongjakso.naverwriterhelper.model.NaverPostData
import com.gptgongjakso.naverwriterhelper.model.PipelineState
import com.gptgongjakso.naverwriterhelper.model.PostHistoryRecord
import com.gptgongjakso.naverwriterhelper.parser.PackageParser
import com.gptgongjakso.naverwriterhelper.parser.TagNormalizer
import com.gptgongjakso.naverwriterhelper.selector.SelectorRules
import com.gptgongjakso.naverwriterhelper.service.FloatingControlService
import com.gptgongjakso.naverwriterhelper.service.NaverAccessibilityService
import com.gptgongjakso.naverwriterhelper.store.AutomationLogStore
import com.gptgongjakso.naverwriterhelper.store.SessionRepository
import com.gptgongjakso.naverwriterhelper.store.db.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val history by lazy { HistoryStore(applicationContext) }

    private val logListener: () -> Unit = { refreshLog() }
    private val sessionListener: () -> Unit = { refreshData(); refreshAutomationUi() }

    /** 시험 모드(작업지시서 11): 실제 이력·발행 기록 없이 선택자/흐름만 확인 */
    private var testMode: Boolean
        get() = SessionRepository.testMode
        set(value) { SessionRepository.testMode = value }

    // 자료 ZIP 선택
    private val openZipLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadPackage(uri)
        }

    // 알림 권한 요청(Android 13+)
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissions()
        }

    // 선택자 JSON 파일 선택(SAF)
    private var selectorLoadMode = SelectorLoadMode.TRIAL
    private val openSelectorLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) applySelectorFile(uri)
        }

    private enum class SelectorLoadMode { TRIAL, OFFICIAL }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AutomationLogStore.init(applicationContext)

        binding.btnLoad.setOnClickListener {
            openZipLauncher.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
            )
        }
        binding.btnAccessibility.setOnClickListener {
            PermissionGuideHelper.openAccessibilitySettings(this)
        }
        binding.btnOverlay.setOnClickListener {
            PermissionGuideHelper.openOverlaySettings(this)
        }
        binding.btnNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !PermissionGuideHelper.hasNotificationPermission(this)
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PermissionGuideHelper.openAppNotificationSettings(this)
            }
        }
        binding.btnSaveImages.setOnClickListener { saveImages() }
        binding.btnOpenNaver.setOnClickListener { openNaver() }
        binding.btnStartFloating.setOnClickListener { startFloating() }

        // 전체 자동 실행 (작업지시서 3, 4, 5)
        binding.btnStartAutomation.setOnClickListener { startFullAutomation() }
        binding.btnAutomationPause.setOnClickListener {
            SessionRepository.orchestrator?.pauseNow("사용자 일시정지") ?: toast("실행 중인 자동화가 없습니다")
        }
        binding.btnAutomationResume.setOnClickListener {
            val o = SessionRepository.orchestrator
            if (o != null && SessionRepository.pipeline.current == PipelineState.PAUSED) o.resumeNow()
            else toast("일시정지 상태가 아닙니다")
        }
        binding.btnAutomationCancel.setOnClickListener {
            SessionRepository.orchestrator?.let {
                it.cancelNow()
                AutomationSessionStore.releaseLock()
                AutomationSessionStore.clear(this)
            } ?: toast("실행 중인 자동화가 없습니다")
        }

        // 선택자 규칙 관리(작업지시서 9.1)
        binding.btnSelectorLoad.setOnClickListener {
            selectorLoadMode = SelectorLoadMode.TRIAL
            openSelectorLauncher.launch(arrayOf("application/json", "*/*"))
        }
        binding.btnSelectorTrial.setOnClickListener {
            selectorLoadMode = SelectorLoadMode.TRIAL
            openSelectorLauncher.launch(arrayOf("application/json", "*/*"))
        }
        binding.btnSelectorApply.setOnClickListener {
            selectorLoadMode = SelectorLoadMode.OFFICIAL
            openSelectorLauncher.launch(arrayOf("application/json", "*/*"))
        }
        binding.btnSelectorRestorePrev.setOnClickListener {
            val ok = SelectorRules.restorePrevious(this)
            NaverAccessibilityService.instance?.reloadSelectorRules()
            toast(if (ok) "이전 정상본으로 복원했습니다" else "이전 정상본이 없습니다")
            refreshSelectorVersion()
        }
        binding.btnSelectorRestoreDefault.setOnClickListener {
            SelectorRules.restoreBuiltinDefault(this)
            NaverAccessibilityService.instance?.reloadSelectorRules()
            toast("내장 기본본으로 복원했습니다")
            refreshSelectorVersion()
        }

        // 발행 상태 기록(지시서 19) — 사용자가 네이버에서 직접 처리한 결과를 기록
        binding.btnMarkPublished.setOnClickListener { markPublish("발행완료", PipelineState.COMPLETED_BY_USER) }
        binding.btnMarkDraft.setOnClickListener { markPublish("임시저장", PipelineState.READY_FOR_USER) }
        binding.btnMarkCancelled.setOnClickListener { markPublish("취소", PipelineState.CANCELLED) }

        binding.switchTestMode.setOnCheckedChangeListener { _, checked ->
            testMode = checked
            AutomationLogStore.add(if (checked) "시험 모드 ON (이력 기록 생략, 발행/임시저장 자동 클릭 절대 금지는 항상 유지)" else "시험 모드 OFF")
        }

        AutomationLogStore.addListener(logListener)
        SessionRepository.addListener(sessionListener)

        refreshData()
        refreshLog()
        refreshSelectorVersion()

        // ZIP 공유/열기 인텐트 처리 (지시서 8)
        handleIncomingIntent(intent)

        // 중단된 자동화 세션 복구 안내(작업지시서 10.3)
        maybeOfferSessionResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshData()
        refreshAutomationUi()
    }

    override fun onDestroy() {
        AutomationLogStore.removeListener(logListener)
        SessionRepository.removeListener(sessionListener)
        super.onDestroy()
    }

    // ---------- 공유/열기 인텐트 → ZIP 로드 (지시서 8) ----------
    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            AutomationLogStore.add("공유/열기로 자료 ZIP 수신")
            loadPackage(uri)
        }
    }

    // ---------- 중단된 자동화 세션 복구 (작업지시서 10.3) ----------
    private fun maybeOfferSessionResume() {
        if (!AutomationSessionStore.hasInterruptedSession(this)) return
        val snap = AutomationSessionStore.load(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("중단된 작업이 있습니다")
            .setMessage(
                "이전 자동화 세션(상태: ${snap.stateMap["current"] ?: "알 수 없음"})이 완료되지 않았습니다.\n" +
                    "이어하려면 같은 자료 ZIP을 다시 불러와 주세요. 자료 해시가 같아야 이어하기가 가능합니다."
            )
            .setPositiveButton("이어하기(자료 다시 불러오기)") { d, _ ->
                d.dismiss()
                pendingResumeSnapshot = snap
                toast("이어할 자료 ZIP을 다시 선택해 주세요")
                openZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
            }
            .setNegativeButton("처음부터 다시") { d, _ ->
                d.dismiss()
                AutomationSessionStore.clear(this)
                AutomationSessionStore.releaseLock()
            }
            .setNeutralButton("취소") { d, _ -> d.dismiss() }
            .show()
    }

    private var pendingResumeSnapshot: AutomationSessionStore.Snapshot? = null

    // ---------- 자료 불러오기 + 처리 파이프라인 ----------
    private fun loadPackage(uri: Uri) {
        // 새 자료 로드 = 세션 초기화 → 이전에 앱이 복사한 클립보드만 조건부 정리
        ClipboardInputHelper.clearIfOwn(this)
        binding.badgeData.text = getString(R.string.badge_loading)

        lifecycleScope.launch {
            // 파싱(PARSING) — IO 스레드
            val parseResult = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        PackageParser.parse(input)
                    } ?: throw IllegalStateException("파일을 열 수 없습니다")
                }
            }

            parseResult.onSuccess { data ->
                SessionRepository.setPostData(data)   // 게시판 자동 매칭 + 상태머신/세션 초기화
                val sm = SessionRepository.pipeline
                sm.transitionTo(PipelineState.VALIDATING)

                // 중복 검사(DUPLICATE_CHECKING)
                sm.transitionTo(PipelineState.DUPLICATE_CHECKING)
                val dup = withContext(Dispatchers.IO) { runDuplicateCheck(data) }
                SessionRepository.lastDuplicateResult = dup
                AutomationLogStore.add("중복 검사 · ${dup.verdict.label} · ${dup.reason}")

                if (dup.verdict == DuplicateVerdict.IDENTICAL && !testMode) {
                    // 동일 글 → 자동 진행 차단(지시서 18). 자료는 보여주되 저장/자동화는 멈춤.
                    sm.pause("동일 글 감지 · 진행 차단")
                    AutomationLogStore.add("동일 글로 판정되어 자동 진행을 멈췄습니다.")
                    refreshData()
                    toast("이미 처리된 동일 글입니다. 진행이 차단되었습니다.")
                    return@onSuccess
                }

                // 이미지 검증(가변, 지시서 7)
                val board = SessionRepository.effectiveBoard()
                val imgResult = ImageValidator.validate(data.images, board, data.metadata.imageCount)
                AutomationLogStore.add("이미지 검증 · ${imgResult.severity} · ${imgResult.issues.firstOrNull() ?: ""}")

                // 이력 저장(STORING) — 시험 모드가 아니면 기록
                sm.transitionTo(PipelineState.STORING)
                if (!testMode) {
                    withContext(Dispatchers.IO) { saveToHistory(data, dup.verdict.label) }
                }

                // 이어하기 복구 시도(작업지시서 10.3): 자료 해시가 일치할 때만 상태를 복원한다.
                val resumeSnap = pendingResumeSnapshot
                pendingResumeSnapshot = null
                if (resumeSnap != null && resumeSnap.zipSha256 == data.zipSha256 && resumeSnap.zipSha256.isNotBlank()) {
                    sm.restoreFrom(resumeSnap.stateMap)
                    SessionRepository.imageManifest = resumeSnap.imageManifest
                    SessionRepository.testMode = resumeSnap.testMode
                    AutomationLogStore.add("이전 세션 상태 복원됨 · ${sm.current.name}")
                    refreshData()
                    toast("이전 세션을 복원했습니다. [이어하기]를 눌러 계속하세요.")
                    return@onSuccess
                } else if (resumeSnap != null) {
                    AutomationLogStore.add("자료 해시가 달라 이전 세션을 복원하지 않았습니다.")
                    toast("선택한 자료가 이전 세션과 달라 새로 시작합니다.")
                }

                // 이미지 자동 변환·저장(가변, 지시서 8) → 준비 완료
                sm.transitionTo(PipelineState.CONVERTING_IMAGES)
                val saveResult = withContext(Dispatchers.IO) {
                    ImageSaveHelper.saveAll(this@MainActivity, data, SessionRepository.sessionId)
                }
                SessionRepository.imageManifest = saveResult.manifest
                val badge = imageSaveBadge(saveResult)
                binding.badgeImageSave.text = badge
                AutomationLogStore.add("이미지 저장: 성공 ${saveResult.success} / 실패 ${saveResult.failed} (앨범: ${ImageSaveHelper.ALBUM_NAME})")
                sm.transitionTo(PipelineState.SAVING_IMAGES)

                if (imgResult.severity == ImageValidator.Severity.ERROR || saveResult.success == 0) {
                    sm.pause("이미지 오류로 자동 실행 준비를 완료하지 못했습니다")
                } else {
                    sm.transitionTo(PipelineState.READY_TO_AUTOMATE)
                }

                AutomationLogStore.add(
                    "자료 준비 완료 · 태그 ${data.tagCount}개 · 이미지 ${data.imageCount}장 · 게시판 ${board.displayName}"
                )
                refreshData()
            }.onFailure { e ->
                SessionRepository.pipeline.fail(e.message ?: "알 수 없는 오류")
                AutomationLogStore.add("자료 불러오기 실패 · ${e.message}")
                binding.badgeData.text = getString(R.string.badge_no_data)
                toast("불러오기 실패: ${e.message}")
                refreshData()
            }
        }
    }

    private fun runDuplicateCheck(data: NaverPostData) =
        DuplicateChecker.check(
            zipSha256 = data.zipSha256,
            bodySha256 = data.bodySha256,
            bodyFingerprint = data.bodyFingerprint,
            title = data.title,
            topicKey = data.metadata.topicKey,
            topicAngle = data.metadata.topicAngle,
            contentVersion = data.metadata.contentVersion,
            postId = data.metadata.postId,
            existing = runCatching { history.all() }.getOrDefault(emptyList()),
            nowMillis = System.currentTimeMillis(),
            windowDays = SessionRepository.effectiveBoard().duplicateWindowDays
        )

    private fun saveToHistory(data: NaverPostData, verdict: String) {
        val postId = data.metadata.postId?.takeIf { it.isNotBlank() }
            ?: "auto-${data.zipSha256.take(16)}"
        val record = PostHistoryRecord(
            postId = postId,
            title = data.title,
            bodyText = data.body,
            boardKey = SessionRepository.effectiveBoard().key,
            topicKey = data.metadata.topicKey,
            topicAngle = data.metadata.topicAngle,
            tags = data.tags,
            createdAt = System.currentTimeMillis(),
            publishStatus = "미확인",
            zipSha256 = data.zipSha256,
            bodySha256 = data.bodySha256,
            bodyFingerprint = data.bodyFingerprint,
            contentVersion = data.metadata.contentVersion,
            lastVerdict = verdict
        )
        runCatching { history.upsert(record) }
    }

    // ---------- 발행 상태 기록 (지시서 19) ----------
    private fun markPublish(status: String, state: PipelineState) {
        val data = SessionRepository.postData ?: run { toast("먼저 자료를 불러오세요"); return }
        val postId = data.metadata.postId?.takeIf { it.isNotBlank() } ?: "auto-${data.zipSha256.take(16)}"
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { history.updatePublishStatus(postId, status) } }
            val sm = SessionRepository.pipeline
            when (state) {
                PipelineState.COMPLETED_BY_USER -> sm.completeByUser()
                PipelineState.CANCELLED -> sm.cancel()
                else -> if (!sm.current.isTerminal) sm.transitionTo(state)
            }
            if (sm.current.isTerminal) {
                AutomationSessionStore.releaseLock()
                AutomationSessionStore.clear(this@MainActivity)
            }
            AutomationLogStore.add("발행 상태 기록 · $status")
            SessionRepository.notifyChanged()
            toast("상태 기록: $status")
        }
    }

    // ---------- 이미지 저장(수동 재시도) ----------
    private fun saveImages() {
        val data = SessionRepository.postData
        if (data == null) {
            toast("먼저 자료를 불러오세요")
            return
        }
        if (data.images.isEmpty()) {
            binding.badgeImageSave.text = "🔴 저장할 이미지 없음"
            AutomationLogStore.add("이미지 저장 실패 · 이미지 없음")
            return
        }
        binding.badgeImageSave.text = getString(R.string.badge_saving)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ImageSaveHelper.saveAll(this@MainActivity, data, SessionRepository.sessionId)
            }
            SessionRepository.imageManifest = result.manifest
            binding.badgeImageSave.text = imageSaveBadge(result)
            AutomationLogStore.add("이미지 저장: 성공 ${result.success} / 실패 ${result.failed} (앨범: ${ImageSaveHelper.ALBUM_NAME})")
        }
    }

    private fun imageSaveBadge(result: ImageSaveHelper.SaveResult): String {
        val board = SessionRepository.effectiveBoard()
        val missing = (board.imageRecommended - result.success).coerceAtLeast(0)
        return when {
            result.allSuccess && missing == 0 -> "✅ 이미지 ${result.success}장 저장 완료"
            result.allSuccess && missing > 0 -> "🟡 이미지 ${result.success}장 저장 · 권장 대비 ${missing}장 부족"
            result.success > 0 -> "🟡 이미지 ${result.success}장 저장 · ${result.failed}장 실패"
            else -> "🔴 이미지 저장 실패"
        }
    }

    // ---------- 네이버 블로그 앱 열기(수동) ----------
    private fun openNaver() {
        SessionRepository.pipeline.let { if (!it.current.isTerminal) it.transitionTo(PipelineState.OPENING_NAVER) }
        when (NaverLaunchHelper.openNaverBlogApp(this)) {
            NaverLaunchHelper.LaunchResult.OPENED_BLOG_APP -> {
                AutomationLogStore.add("네이버 블로그 앱 실행 완료")
                toast("네이버 블로그 앱에서 글쓰기 화면을 연 뒤 플로팅 컨트롤을 사용하세요.")
            }
            NaverLaunchHelper.LaunchResult.BLOG_APP_NOT_INSTALLED -> {
                AutomationLogStore.add("네이버 블로그 앱 미설치")
                showBlogAppInstallGuideDialog()
            }
            NaverLaunchHelper.LaunchResult.FAILED -> {
                AutomationLogStore.add("네이버 블로그 앱 실행 실패")
                toast("네이버 블로그 앱을 열 수 없습니다. 설치 상태를 확인해 주세요.")
            }
        }
    }

    /** 네이버 블로그 앱 미설치 안내 팝업 */
    private fun showBlogAppInstallGuideDialog() {
        AlertDialog.Builder(this)
            .setMessage("네이버 블로그 앱이 필요합니다.\n모바일 글쓰기는 네이버 블로그 앱에서 진행해 주세요.")
            .setPositiveButton("설치 화면 열기") { dialog, _ ->
                dialog.dismiss()
                val opened = NaverLaunchHelper.openPlayStoreForBlogApp(this)
                if (opened) {
                    AutomationLogStore.add("Play 스토어 설치 화면 실행")
                    toast("설치 완료 후 GPT 공작소로 돌아와 다시 눌러주세요.")
                } else {
                    AutomationLogStore.add("Play 스토어 설치 화면 실행 실패")
                    toast("Play 스토어를 열 수 없습니다. 직접 설치해 주세요.")
                }
            }
            .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ---------- 플로팅 시작 ----------
    private fun startFloating() {
        if (!PermissionGuideHelper.canDrawOverlays(this)) {
            toast("다른 앱 위에 표시 권한이 필요합니다")
            PermissionGuideHelper.openOverlaySettings(this)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionGuideHelper.hasNotificationPermission(this)
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val intent = Intent(this, FloatingControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        toast("플로팅 컨트롤을 시작했습니다")
    }

    // ---------- 전체 자동 실행 (작업지시서 3, 4, 5) ----------

    /** 버튼 활성 조건(작업지시서 4.1)이 부족하면 한글 사유 목록을 돌려준다. 비어 있으면 시작 가능. */
    private fun computeAutomationBlockers(): List<String> {
        val blockers = ArrayList<String>()
        val data = SessionRepository.postData
        if (data == null) {
            blockers.add("정상 파싱된 자료 없음")
            return blockers
        }
        val dup = SessionRepository.lastDuplicateResult
        if (dup?.verdict == DuplicateVerdict.IDENTICAL) blockers.add("동일 글로 차단된 상태")
        val board = SessionRepository.effectiveBoard()
        val imgResult = ImageValidator.validate(data.images, board, data.metadata.imageCount)
        if (imgResult.severity == ImageValidator.Severity.ERROR) blockers.add("이미지 검증 오류")
        if (SessionRepository.imageManifest.isEmpty()) blockers.add("저장된 이미지 없음")
        if (!PermissionGuideHelper.isAccessibilityEnabled(this)) blockers.add("접근성 허용 필요")
        if (!PermissionGuideHelper.canDrawOverlays(this)) blockers.add("오버레이 허용 필요")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionGuideHelper.hasNotificationPermission(this)
        ) blockers.add("알림 허용 필요")
        if (!NaverLaunchHelper.isBlogAppInstalled(this)) blockers.add("네이버 블로그 앱 미설치")
        if (AutomationSessionStore.isLocked()) blockers.add("다른 자동화 세션 실행 중")
        return blockers
    }

    private fun startFullAutomation() {
        val blockers = computeAutomationBlockers()
        if (blockers.isNotEmpty()) {
            toast("확인 필요: ${blockers.joinToString(", ")}")
            refreshAutomationUi()
            return
        }
        if (!AutomationSessionStore.tryAcquireLock()) {
            toast("이미 다른 자동화가 실행 중입니다")
            return
        }
        if (!PermissionGuideHelper.canDrawOverlays(this)) {
            AutomationSessionStore.releaseLock()
            toast("오버레이 권한이 필요합니다")
            return
        }
        startFloating()

        val sm = SessionRepository.pipeline
        if (sm.current == PipelineState.READY_TO_AUTOMATE) {
            val orchestrator = buildOrchestrator(sm)
            SessionRepository.orchestrator = orchestrator
            orchestrator.start()
        } else if (sm.current == PipelineState.PAUSED || sm.current.isAutomationStep) {
            // 프로세스 재생성 등으로 PAUSED 를 거치지 않고 중단된 경우도 안전하게 일시정지 처리 후 이어한다.
            if (sm.current != PipelineState.PAUSED) sm.pause("앱 재시작 후 이어하기")
            val orchestrator = buildOrchestrator(sm)
            SessionRepository.orchestrator = orchestrator
            orchestrator.resumeNow()
        } else {
            AutomationSessionStore.releaseLock()
            toast("자동 실행을 시작할 수 없는 상태입니다: ${sm.current.name}")
            return
        }
        toast("전체 자동 실행을 시작합니다")
        refreshAutomationUi()
    }

    private fun buildOrchestrator(sm: com.gptgongjakso.naverwriterhelper.statemachine.PipelineStateMachine): AutomationOrchestrator {
        val executor = AutomationStepExecutorImpl(this)
        val timeoutController = StepTimeoutController()
        return AutomationOrchestrator(
            stateMachine = sm,
            executor = executor,
            boardProfile = { SessionRepository.selectedBoard },
            naverCategory = { SessionRepository.naverCategory() },
            expectedPhotoCount = { SessionRepository.imageManifest.size },
            scheduleNext = { delay, action -> timeoutController.arm(delay, action) },
            onUpdate = {
                SessionRepository.notifyChanged()
                persistSession()
            },
            onLog = { AutomationLogStore.add(it) }
        )
    }

    private fun persistSession() {
        val data = SessionRepository.postData ?: return
        AutomationSessionStore.save(
            context = this,
            sessionId = SessionRepository.sessionId,
            zipSha256 = data.zipSha256,
            testMode = SessionRepository.testMode,
            stateMachine = SessionRepository.pipeline,
            imageManifest = SessionRepository.imageManifest,
            selectedBoardKey = SessionRepository.selectedBoard?.key
        )
        if (SessionRepository.pipeline.current.isTerminal) {
            AutomationSessionStore.releaseLock()
        }
    }

    // ---------- 선택자 규칙 관리(작업지시서 9.1) ----------
    private fun applySelectorFile(uri: Uri) {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                    .getOrNull()
            }
            if (json == null) {
                toast("파일을 읽지 못했습니다")
                return@launch
            }
            val result = if (selectorLoadMode == SelectorLoadMode.OFFICIAL) {
                SelectorRules.applyOfficial(this@MainActivity, json)
            } else {
                SelectorRules.applyTrial(this@MainActivity, json)
            }
            when (result) {
                is SelectorRules.ValidationResult.Valid -> {
                    NaverAccessibilityService.instance?.reloadSelectorRules()
                    val modeLabel = if (selectorLoadMode == SelectorLoadMode.OFFICIAL) "정식 적용" else "시험 적용"
                    toast("$modeLabel 완료 (규칙 버전: ${result.ruleSet.rulesVersion})")
                    AutomationLogStore.add("선택자 규칙 $modeLabel · ${result.ruleSet.rulesVersion}")
                }
                is SelectorRules.ValidationResult.Invalid -> {
                    toast("잘못된 선택자 JSON: ${result.reason}")
                    AutomationLogStore.add("선택자 규칙 적용 실패 · ${result.reason}")
                }
            }
            refreshSelectorVersion()
        }
    }

    private fun refreshSelectorVersion() {
        val rules = SelectorRules.loadActive(this)
        binding.txtSelectorVersion.text =
            "현재 규칙: ${rules.rulesVersion} (${rules.source.name}) · 대상: ${rules.targetPackage}"
    }

    // ---------- 화면 갱신 ----------
    private fun refreshData() {
        val data = SessionRepository.postData
        if (data == null) {
            binding.badgeData.text = getString(R.string.badge_no_data)
            binding.txtTitle.text = "제목: -"
            binding.txtBody.text = "본문: -"
            binding.txtTags.text = "태그: -"
            binding.txtImages.text = "이미지: -"
            binding.txtBoard.text = "게시판: -"
            binding.txtDuplicate.text = "중복 검사: -"
            binding.txtImageValidation.text = "이미지 검증: -"
            binding.txtStateStatus.text = "상태: -"
            return
        }
        binding.badgeData.text = getString(R.string.badge_ready)
        binding.txtTitle.text = "제목: ${data.title}"
        binding.txtBody.text = "본문: ${data.bodyLength}자"
        binding.txtTags.text = "태그: ${data.tagCount}개  ${TagNormalizer.toDisplayString(data.tags)}"

        val board = SessionRepository.effectiveBoard()
        val imgResult = ImageValidator.validate(data.images, board, data.metadata.imageCount)
        binding.txtImages.text = "이미지: ${data.imageCount}장 (권장 ${board.imageRecommended})"
        binding.txtBoard.text =
            if (SessionRepository.selectedBoard != null) "게시판: ${board.displayName} (자동 매칭)"
            else "게시판: 미매칭 → 기본(${board.displayName}) 기준 검증"
        binding.txtImageValidation.text = "이미지 검증: ${imgResult.severity} · ${imgResult.issues.joinToString(" / ")}"

        val dup = SessionRepository.lastDuplicateResult
        binding.txtDuplicate.text =
            if (dup != null) "중복 검사: ${dup.verdict.label} · ${dup.reason}" else "중복 검사: 대기"

        binding.txtStateStatus.text = "상태: ${SessionRepository.pipeline.current.name}" +
            if (testMode) " · [시험 모드]" else ""
    }

    private fun refreshAutomationUi() {
        val blockers = computeAutomationBlockers()
        binding.txtAutomationConditions.text =
            if (blockers.isEmpty()) "전체 자동 실행 준비 완료" else "부족한 항목: ${blockers.joinToString(", ")}"
        binding.btnStartAutomation.isEnabled = blockers.isEmpty()

        val sm = SessionRepository.pipeline
        val orchestrator = SessionRepository.orchestrator
        val runningBadge = when {
            orchestrator?.running == true -> "자동 실행 중"
            sm.current == PipelineState.PAUSED -> "일시정지"
            sm.current == PipelineState.READY_FOR_USER -> "자동 실행 완료 · 발행 대기"
            else -> "대기"
        }
        val reason = sm.pauseReason?.let { " · 사유: $it" } ?: ""
        binding.txtAutomationStatus.text =
            "[$runningBadge] ${sm.statusSummary()} · 태그 ${SessionRepository.tagController.progressText()} " +
                "· 사진 ${sm.selectedPhotoCount}/${sm.totalPhotoCount} · 재시도 ${sm.retriesOf(sm.current)}회$reason"
    }

    private fun refreshPermissions() {
        binding.badgeAccessibility.text =
            if (PermissionGuideHelper.isAccessibilityEnabled(this)) getString(R.string.badge_granted_green)
            else getString(R.string.badge_need)
        binding.badgeOverlay.text =
            if (PermissionGuideHelper.canDrawOverlays(this)) getString(R.string.badge_granted_green)
            else getString(R.string.badge_need)
        binding.badgePhoto.text = getString(R.string.badge_ready_green)
        binding.badgeNotification.text =
            if (PermissionGuideHelper.hasNotificationPermission(this)) getString(R.string.badge_granted_green)
            else getString(R.string.badge_need)
    }

    private fun refreshLog() {
        val logs = AutomationLogStore.recent(6)
        binding.txtLog.text = if (logs.isEmpty()) "아직 로그가 없습니다." else logs.joinToString("\n")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
