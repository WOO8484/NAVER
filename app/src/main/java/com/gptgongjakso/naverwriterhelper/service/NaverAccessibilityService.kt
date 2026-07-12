package com.gptgongjakso.naverwriterhelper.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.gptgongjakso.naverwriterhelper.automation.ActionResult
import com.gptgongjakso.naverwriterhelper.helper.ClipboardInputHelper
import com.gptgongjakso.naverwriterhelper.helper.NaverLaunchHelper
import com.gptgongjakso.naverwriterhelper.selector.SelectorRules
import com.gptgongjakso.naverwriterhelper.store.AutomationLogStore
import com.gptgongjakso.naverwriterhelper.store.SessionRepository

/**
 * 네이버 글쓰기 화면 입력을 보조하는 접근성 서비스. v0.1.1 검증 로직 그대로 이식.
 *
 * 설계 원칙(변경 없음):
 *  - 발행/임시저장 버튼은 절대 누르지 않는다. (탐색 대상에서 제외)
 *  - 모든 자동 입력은 실패해도 앱이 죽지 않고 "클립보드 복사 + 안내" 로 fallback.
 *  - 자동 조작 전 현재 화면이 허용된 네이버 블로그 앱 패키지인지 공통 검사.
 *    네이버 블로그 앱이 아니면 어떤 노드 조작도 하지 않고 클립보드 fallback 으로 전환.
 *    (→ 앱 전환/전화 수신 시 다른 앱이 전면으로 오면 자동으로 중단됨: 지시서 12 안전)
 *
 * v1.0.0 추가:
 *  - 단계별 타임아웃(지시서 15): 태그 1개 입력이 제한 시간 내 확정되지 않으면 중단·fallback.
 *    (게시판/제목/본문/사진 단계 타임아웃은 오케스트레이터(FloatingControlService)가 사용)
 */
class NaverAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: NaverAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /** 네이버 블로그 앱 화면이 아닐 때의 공통 안내 문구 */
        private const val MSG_NOT_NAVER =
            "네이버 블로그 앱 화면이 아니므로 자동 입력하지 않았습니다. 클립보드 복사로 전환합니다."

        /** fallback 클립보드 조건부 삭제 지연(2분) */
        private const val FALLBACK_CLEAR_DELAY = 120_000L

        /** 자동 붙여넣기 성공 후 클립보드 조건부 삭제 지연(3초) */
        private const val SUCCESS_CLEAR_DELAY = 3_000L

        // ---- 단계별 타임아웃 (지시서 15) ----
        const val TIMEOUT_CATEGORY_MS = 10_000L
        const val TIMEOUT_TITLE_MS = 10_000L
        const val TIMEOUT_BODY_MS = 20_000L
        const val TIMEOUT_TAG_MS = 5_000L
        const val TIMEOUT_PHOTO_MS = 10_000L

        /** 태그 사이 대기(IME 확정 안정화) */
        private const val TAG_STEP_DELAY = 500L
    }

    /** 접근성 조작을 허용하는 패키지 (네이버 블로그 앱 1개만, v1.0.1) */
    private val allowedPackages = setOf(
        "com.nhn.android.blog"
    )

    /** 사진 첨부 탐색 시 절대 클릭하면 안 되는 단어 */
    private val forbiddenClickWords = listOf(
        "발행", "등록", "완료", "게시", "공개", "저장", "임시저장", "예약", "확인"
    )

    /** 최근 접근성 이벤트의 패키지명(패키지 판별 보조) */
    @Volatile
    private var lastEventPackage: String? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 현재 태그 스텝의 타임아웃 감시 토큰 */
    private var tagTimeoutRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadSelectorRules()
        AutomationLogStore.add("접근성 서비스 연결됨")
        SessionRepository.notifyChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 온디맨드 방식이라 이벤트로 조작하지 않지만, 패키지 판별용으로만 기록한다.
        event?.packageName?.toString()?.let { lastEventPackage = it }
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        AutomationLogStore.add("접근성 서비스 해제됨")
        SessionRepository.notifyChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ======================= 보안 공통 검사 =======================

    /** 현재 활성 화면이 허용된 네이버 패키지인지 */
    private fun isAllowedNaverPackage(): Boolean {
        val current = rootInActiveWindow?.packageName?.toString()
        if (current != null) return current in allowedPackages
        // 활성 창 패키지를 못 읽으면 최근 이벤트 패키지로 보조 판별
        val fallback = lastEventPackage
        return fallback != null && fallback in allowedPackages
    }

    /** 현재 화면이 글쓰기(편집) 화면으로 볼 만한 신호가 있는지 (과도하게 엄격하지 않게) */
    private fun isLikelyWriteScreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        var editableCount = 0
        var hasWriteSignal = false
        traverse(root) { node ->
            if (node.isEditable) editableCount++
            val hint = node.hintText?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val vid = node.viewIdResourceName ?: ""
            if (listOf("제목", "본문", "태그", "내용", "글쓰기", "쓰기").any { hint.contains(it) || text.contains(it) } ||
                listOf("title", "content", "body", "tag", "write", "post", "subject").any { vid.contains(it, ignoreCase = true) }
            ) {
                hasWriteSignal = true
            }
        }
        return editableCount >= 1 || hasWriteSignal
    }

    // ======================= 공개 액션 =======================

    /** 제목 자동 입력. 허용 패키지/편집칸 확인 후 진행, 아니면 클립보드 fallback. */
    fun inputTitle(context: Context): Boolean {
        val data = SessionRepository.postData ?: run {
            AutomationLogStore.add("제목 입력 실패 · 불러온 자료 없음")
            return false
        }
        if (!isAllowedNaverPackage()) {
            ClipboardInputHelper.copyWithAutoClear(context, "제목", data.title, FALLBACK_CLEAR_DELAY)
            AutomationLogStore.add("제목 · $MSG_NOT_NAVER")
            return false
        }
        val node = focusedEditable() ?: if (isLikelyWriteScreen()) topMostEditable() else null
        val ok = node != null && setNodeText(node, data.title)
        if (ok) {
            AutomationLogStore.add("제목 입력 완료")
        } else {
            ClipboardInputHelper.copyWithAutoClear(context, "제목", data.title, FALLBACK_CLEAR_DELAY)
            AutomationLogStore.add("제목 자동 입력 실패 · 클립보드 복사됨(입력칸에 붙여넣기)")
        }
        return ok
    }

    /** 본문 자동 입력(클립보드 붙여넣기 우선). 허용 패키지/편집칸 확인 후 진행. */
    fun inputBody(context: Context): Boolean {
        val data = SessionRepository.postData ?: run {
            AutomationLogStore.add("본문 입력 실패 · 불러온 자료 없음")
            return false
        }
        if (!isAllowedNaverPackage()) {
            ClipboardInputHelper.copyWithAutoClear(context, "본문", data.body, FALLBACK_CLEAR_DELAY)
            AutomationLogStore.add("본문 · $MSG_NOT_NAVER")
            return false
        }
        // 붙여넣기용으로 클립보드에 복사
        ClipboardInputHelper.copy(context, "본문", data.body)
        val node = focusedEditable() ?: if (isLikelyWriteScreen()) largestEditable() else null
        val ok = node != null && pasteToNode(node)
        if (ok) {
            AutomationLogStore.add("본문 입력 완료")
            ClipboardInputHelper.scheduleClear(context, data.body, SUCCESS_CLEAR_DELAY)
        } else {
            AutomationLogStore.add("본문 자동 입력 실패 · 클립보드 복사됨(입력칸 길게 눌러 붙여넣기)")
            ClipboardInputHelper.scheduleClear(context, data.body, FALLBACK_CLEAR_DELAY)
        }
        return ok
    }

    /**
     * 태그 전체를 "1개씩" 자동 입력한다. (절대 한 번에 붙여넣지 않음)
     * 각 태그: 입력 → IME_ENTER(확정) → 대기 → 다음.
     * v1.0.0: 각 태그 스텝에 타임아웃(TIMEOUT_TAG_MS) 감시 추가.
     */
    fun autoInputAllTags(
        context: Context,
        onEach: (progress: String) -> Unit = {},
        onFinish: (completed: Boolean) -> Unit = {}
    ) {
        val controller = SessionRepository.tagController
        if (controller.isEmpty()) {
            AutomationLogStore.add("태그 입력 실패 · 태그 없음")
            onFinish(false)
            return
        }
        if (!isAllowedNaverPackage()) {
            controller.current()?.let { ClipboardInputHelper.copyWithAutoClear(context, "태그", it, FALLBACK_CLEAR_DELAY) }
            AutomationLogStore.add("태그 · $MSG_NOT_NAVER")
            onFinish(false)
            return
        }
        stepInputTag(context, onEach, onFinish)
    }

    private fun stepInputTag(
        context: Context,
        onEach: (String) -> Unit,
        onFinish: (Boolean) -> Unit
    ) {
        val controller = SessionRepository.tagController
        if (!controller.hasNext()) {
            cancelTagTimeout()
            AutomationLogStore.add("태그 ${controller.progressText()} 입력 완료 · 전체 완료")
            SessionRepository.notifyChanged()
            onFinish(true)
            return
        }

        // 진행 중 화면이 바뀌어 네이버가 아니게 되면 즉시 중단(안전)
        if (!isAllowedNaverPackage()) {
            cancelTagTimeout()
            controller.current()?.let { ClipboardInputHelper.copyWithAutoClear(context, "태그", it, FALLBACK_CLEAR_DELAY) }
            AutomationLogStore.add("태그 · $MSG_NOT_NAVER")
            onFinish(false)
            return
        }

        val tag = controller.current() ?: run { onFinish(true); return }

        // v1.0.0: 태그 스텝 타임아웃 감시 시작 — 제한 시간 내 다음 스텝으로 넘어가지 못하면 중단.
        armTagTimeout(context, onFinish)

        // 글쓰기 불확실 시 포커스된 편집칸이 있을 때만 입력
        val field = focusedEditable() ?: if (isLikelyWriteScreen()) findTagField() else null

        val typed = field != null && setNodeText(field, tag)
        val entered = typed && field != null && imeEnter(field)

        if (typed && entered) {
            cancelTagTimeout()
            controller.markCurrentDone()
            AutomationLogStore.add("태그 ${controller.progressText()} 입력 완료") // 태그 내용은 로그에 남기지 않음
            SessionRepository.notifyChanged()
            onEach(controller.progressText())
            handler.postDelayed({ stepInputTag(context, onEach, onFinish) }, TAG_STEP_DELAY)
        } else {
            cancelTagTimeout()
            ClipboardInputHelper.copyWithAutoClear(context, "태그", tag, FALLBACK_CLEAR_DELAY)
            AutomationLogStore.add("태그 자동 입력 실패 · 현재 태그를 클립보드에 복사(붙여넣고 Enter)")
            onFinish(false)
        }
    }

    /** 태그 스텝 타임아웃을 건다. 시간 내 해제되지 않으면 안전하게 중단. */
    private fun armTagTimeout(context: Context, onFinish: (Boolean) -> Unit) {
        cancelTagTimeout()
        val r = Runnable {
            val controller = SessionRepository.tagController
            controller.current()?.let { ClipboardInputHelper.copyWithAutoClear(context, "태그", it, FALLBACK_CLEAR_DELAY) }
            AutomationLogStore.add("태그 입력 시간 초과 · 자동 진행을 중단하고 현재 태그를 복사했습니다.")
            onFinish(false)
        }
        tagTimeoutRunnable = r
        handler.postDelayed(r, TIMEOUT_TAG_MS)
    }

    private fun cancelTagTimeout() {
        tagTimeoutRunnable?.let { handler.removeCallbacks(it) }
        tagTimeoutRunnable = null
    }

    /** 다음 태그 1개를 클립보드에 복사하고 커서를 넘긴다(수동 입력 모드). */
    fun copyNextTag(context: Context): Boolean {
        val controller = SessionRepository.tagController
        val tag = controller.current() ?: run {
            AutomationLogStore.add("복사할 다음 태그 없음")
            return false
        }
        ClipboardInputHelper.copyWithAutoClear(context, "태그", tag, FALLBACK_CLEAR_DELAY)
        controller.markCurrentDone()
        AutomationLogStore.add("다음 태그 복사 (${controller.progressText()})") // 태그 내용 미기록
        SessionRepository.notifyChanged()
        return true
    }

    /** 방금(현재 커서 직전) 태그를 다시 클립보드에 복사. */
    fun copyCurrentTagAgain(context: Context): Boolean {
        val controller = SessionRepository.tagController
        val idx = (controller.cursor - 1).coerceAtLeast(0)
        val tag = controller.snapshot().getOrNull(idx) ?: controller.current() ?: run {
            AutomationLogStore.add("다시 복사할 태그 없음")
            return false
        }
        ClipboardInputHelper.copyWithAutoClear(context, "태그", tag, FALLBACK_CLEAR_DELAY)
        AutomationLogStore.add("현재 태그 다시 복사") // 태그 내용 미기록
        return true
    }

    /**
     * 사진 첨부 버튼을 안전하게 찾아 클릭 시도(없으면 안내만).
     * 발행/등록/저장 계열 버튼은 절대 누르지 않는다.
     */
    fun openPhotoAttach(context: Context): Boolean {
        if (!isAllowedNaverPackage()) {
            AutomationLogStore.add("사진 첨부 · $MSG_NOT_NAVER")
            return false
        }
        if (!isLikelyWriteScreen()) {
            AutomationLogStore.add("사진 버튼을 찾지 못했습니다. 네이버 화면에서 직접 눌러주세요.")
            return false
        }
        val root = rootInActiveWindow ?: run {
            AutomationLogStore.add("사진 버튼을 찾지 못했습니다. 네이버 화면에서 직접 눌러주세요.")
            return false
        }

        val candidates = listOf("사진", "이미지", "photo", "image")
        for (word in candidates) {
            val matched = findNodeByText(root, word) ?: continue
            val clickable = clickableAncestor(matched) ?: continue
            // 발행/등록/저장 계열이면 클릭 금지
            if (hasForbiddenWordNear(clickable) || hasForbiddenWordNear(matched)) continue
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AutomationLogStore.add("사진 첨부 화면 이동 시도 · GPT공작소 앨범에서 이미지를 선택하세요")
                return true
            }
        }
        AutomationLogStore.add("사진 버튼을 찾지 못했습니다. 네이버 화면에서 직접 눌러주세요.")
        return false
    }

    // ======================= 노드 탐색/조작 (v0.1.1 유지) =======================

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    private fun pasteToNode(node: AccessibilityNodeInfo): Boolean {
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    /** IME 확정(Enter). API 30+ ACTION_IME_ENTER 사용. */
    private fun imeEnter(node: AccessibilityNodeInfo): Boolean {
        return runCatching {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }.getOrDefault(false)
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable) node else null
    }

    private fun collectEditable(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val list = ArrayList<AccessibilityNodeInfo>()
        traverse(root) { if (it.isEditable) list.add(it) }
        return list
    }

    private fun topMostEditable(): AccessibilityNodeInfo? =
        collectEditable().minByOrNull { boundsOf(it).top }

    private fun largestEditable(): AccessibilityNodeInfo? =
        collectEditable().maxByOrNull { boundsOf(it).height() }

    private fun findTagField(): AccessibilityNodeInfo? {
        val editable = collectEditable()
        editable.firstOrNull { node ->
            val t = (node.text?.toString() ?: "")
            val h = (node.hintText?.toString() ?: "")
            t.contains("태그") || h.contains("태그")
        }?.let { return it }
        return editable.maxByOrNull { boundsOf(it).top }
    }

    /** 텍스트/설명에 word 가 포함된 첫 노드 */
    private fun findNodeByText(root: AccessibilityNodeInfo, word: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        traverse(root) { node ->
            if (found != null) return@traverse
            val text = (node.text?.toString() ?: "")
            val desc = (node.contentDescription?.toString() ?: "")
            if (text.contains(word, ignoreCase = true) || desc.contains(word, ignoreCase = true)) {
                found = node
            }
        }
        return found
    }

    /** 노드 자신과 가까운 상위 노드에 금지 단어가 있으면 true */
    private fun hasForbiddenWordNear(node: AccessibilityNodeInfo?): Boolean {
        var cur = node
        var depth = 0
        while (cur != null && depth < 4) {
            val text = (cur.text?.toString() ?: "")
            val desc = (cur.contentDescription?.toString() ?: "")
            val hint = (cur.hintText?.toString() ?: "")
            if (forbiddenClickWords.any { text.contains(it) || desc.contains(it) || hint.contains(it) }) {
                return true
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val r = Rect()
        node.getBoundsInScreen(r)
        return r
    }

    private fun traverse(node: AccessibilityNodeInfo?, action: (AccessibilityNodeInfo) -> Unit) {
        node ?: return
        action(node)
        for (i in 0 until node.childCount) {
            traverse(node.getChild(i), action)
        }
    }

    // =====================================================================================
    // v1.1.0 오케스트레이터 연동 (작업지시서 5, 6, 7, 8, 9, 12, 13)
    //
    // 아래 함수들은 각각 "화면 탐색 + 1개 동작"만 수행하고 [ActionResult] 를 돌려준다.
    // 전체 순서(다음에 무엇을 할지, 몇 번 재시도할지, 언제 일시정지할지)는
    // AutomationOrchestrator 가 결정한다 — 이 서비스는 순서를 스스로 진행하지 않는다.
    // 선택자는 [SelectorRules] 를 통해 로드하며, JSON이 깨져 있으면 안전하게 빈 규칙으로
    // 동작해 아무 것도 잘못 클릭하지 않는다(찾지 못함 → Retryable/NeedsUser).
    // =====================================================================================

    enum class ScreenId { NAVER_HOME, WRITE_EDITOR, CATEGORY_DIALOG, PHOTO_PICKER, UNKNOWN }

    @Volatile
    private var cachedRules: SelectorRules.RuleSet? = null

    /** selector_rules.json 을 사용자가 새로 적용했을 때 오케스트레이터가 호출한다. */
    fun reloadSelectorRules() {
        cachedRules = SelectorRules.loadActive(this)
    }

    private fun ensureRules(): SelectorRules.RuleSet =
        cachedRules ?: SelectorRules.loadActive(this).also { cachedRules = it }

    /** 현재 전면 화면의 패키지명(패키지 판별 보조 포함) */
    fun currentPackage(): String? =
        rootInActiveWindow?.packageName?.toString() ?: lastEventPackage

    /** 선택자 후보 1개가 노드와 일치하는지(조건 조합, 지시서 9.2) */
    private fun matchesCandidate(node: AccessibilityNodeInfo, c: SelectorRules.Candidate): Boolean {
        val viewId = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val hint = node.hintText?.toString() ?: ""
        val className = node.className?.toString() ?: ""

        c.viewIdContains?.let { if (!viewId.contains(it, ignoreCase = true)) return false }
        c.textExact?.let { if (text != it) return false }
        c.textContains?.let { if (!text.contains(it, ignoreCase = true)) return false }
        c.descExact?.let { if (desc != it) return false }
        c.descContains?.let { if (!desc.contains(it, ignoreCase = true)) return false }
        c.hintContains?.let { if (!hint.contains(it, ignoreCase = true)) return false }
        c.className?.let { if (className != it) return false }
        if (c.editableOnly && !node.isEditable) return false
        if (c.clickableOnly && !node.isClickable && clickableAncestor(node) == null) return false

        // 최소 1개 조건은 있어야 매치로 인정(빈 후보가 전체를 매치하지 않도록)
        val hasAnyCondition = listOf(
            c.viewIdContains, c.textExact, c.textContains, c.descExact,
            c.descContains, c.hintContains, c.className
        ).any { it != null } || c.editableOnly || c.clickableOnly
        return hasAnyCondition
    }

    /** 후보 목록을 우선순위대로 시도해 첫 번째로 매치되는 노드 1개를 찾는다(지시서 9.3). */
    private fun findFirst(candidates: List<SelectorRules.Candidate>): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        for (candidate in candidates) {
            var found: AccessibilityNodeInfo? = null
            traverse(root) { node ->
                if (found == null && matchesCandidate(node, candidate)) found = node
            }
            if (found != null) return found
        }
        return null
    }

    /** 첫 번째로 매치가 있는 후보의 모든 노드를 반환한다(사진 그리드처럼 여러 개가 필요할 때). */
    private fun findAll(candidates: List<SelectorRules.Candidate>): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        for (candidate in candidates) {
            val out = ArrayList<AccessibilityNodeInfo>()
            traverse(root) { node -> if (matchesCandidate(node, candidate)) out.add(node) }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private fun matchesSignals(root: AccessibilityNodeInfo, signals: List<SelectorRules.Candidate>): Boolean {
        if (signals.isEmpty()) return false
        var matched = false
        traverse(root) { node -> if (!matched && signals.any { matchesCandidate(node, it) }) matched = true }
        return matched
    }

    /** 현재 화면을 식별한다. 네이버 블로그 앱이 아니면서 등록된 사진 선택기 패키지면 PHOTO_PICKER. */
    fun identifyScreen(): ScreenId {
        val rules = ensureRules()
        val pkg = currentPackage()
        val photoPickerPackages = rules.screen("PHOTO_PICKER").packageNames
        if (photoPickerPackages.isNotEmpty() && pkg != null && pkg in photoPickerPackages) {
            return ScreenId.PHOTO_PICKER
        }
        if (pkg != NaverLaunchHelper.PKG_BLOG) return ScreenId.UNKNOWN
        val root = rootInActiveWindow ?: return ScreenId.UNKNOWN
        if (matchesSignals(root, rules.screen("CATEGORY_DIALOG").signals)) return ScreenId.CATEGORY_DIALOG
        if (matchesSignals(root, rules.screen("WRITE_EDITOR").signals)) return ScreenId.WRITE_EDITOR
        if (matchesSignals(root, rules.screen("NAVER_HOME").signals)) return ScreenId.NAVER_HOME
        return ScreenId.UNKNOWN
    }

    /**
     * 문맥별 안전 클릭(지시서 7.3/13): 위험 단어 근접 검사 + (candidate.screenRole 이 있으면)
     * 현재 화면 역할이 정확히 일치하는지까지 재검사한 뒤에만 클릭한다.
     */
    private fun safeClick(node: AccessibilityNodeInfo, requiredScreenRole: String? = null): ActionResult {
        if (hasForbiddenWordNear(node)) {
            AutomationLogStore.add("안전 클릭 차단 · 위험 단어 근접 노드")
            return ActionResult.Blocked("위험 버튼(발행/등록/게시/공개/임시저장/예약 계열) 근접 · 클릭하지 않음")
        }
        if (requiredScreenRole != null && identifyScreen().name != requiredScreenRole) {
            return ActionResult.Blocked("화면 문맥 불일치(필요: $requiredScreenRole, 현재: ${identifyScreen().name})")
        }
        val clickable = clickableAncestor(node) ?: node
        val ok = runCatching { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        return if (ok) ActionResult.Retryable("클릭 시도됨 · 결과 확인 필요") else ActionResult.Retryable("클릭 실패")
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val clickable = clickableAncestor(node) ?: node
        return runCatching { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    }

    // ---------- 글쓰기 화면 진입 (지시서 3.2 이식 확장) ----------

    fun openWriteScreenStep(): ActionResult {
        if (currentPackage() != NaverLaunchHelper.PKG_BLOG) {
            return ActionResult.NeedsUser("네이버 블로그 앱 화면이 아닙니다.")
        }
        val rules = ensureRules()
        return when (identifyScreen()) {
            ScreenId.WRITE_EDITOR -> ActionResult.Success("이미 글쓰기 화면")
            ScreenId.NAVER_HOME -> {
                val btn = findFirst(rules.screen("NAVER_HOME").writeButton)
                    ?: return ActionResult.NeedsUser("네이버 블로그 앱에서 글쓰기 화면을 열어 주세요. 화면이 확인되면 자동으로 이어서 진행합니다.")
                if (clickNode(btn)) ActionResult.Retryable("글쓰기 버튼 클릭 시도 · 화면 전환 확인 필요")
                else ActionResult.Retryable("글쓰기 버튼 클릭 실패")
            }
            else -> ActionResult.NeedsUser("네이버 블로그 앱에서 글쓰기 화면을 열어 주세요. 화면이 확인되면 자동으로 이어서 진행합니다.")
        }
    }

    fun verifyWriteScreenStep(): ActionResult =
        if (identifyScreen() == ScreenId.WRITE_EDITOR) ActionResult.Success("글쓰기 화면 확인됨")
        else ActionResult.Retryable("글쓰기 화면이 아직 확인되지 않음")

    // ---------- 게시판(카테고리) 자동 선택 (지시서 7) ----------

    fun openCategoryStep(): ActionResult {
        if (identifyScreen() != ScreenId.WRITE_EDITOR) return ActionResult.Retryable("글쓰기 화면 아님")
        val rules = ensureRules()
        val btn = findFirst(rules.screen("WRITE_EDITOR").categoryButton)
            ?: return ActionResult.Retryable("카테고리 버튼을 찾지 못함")
        return if (clickNode(btn)) ActionResult.Retryable("카테고리 버튼 클릭 시도")
        else ActionResult.Retryable("카테고리 버튼 클릭 실패")
    }

    /** 카테고리 목록 화면에 실제로 표시된 텍스트를 모두 수집한다(BoardMatcher 매칭용). */
    fun collectCategoryTexts(): List<String> {
        if (identifyScreen() != ScreenId.CATEGORY_DIALOG) return emptyList()
        val rules = ensureRules()
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<String>()
        val candidates = rules.screen("CATEGORY_DIALOG").categoryItems
        traverse(root) { node ->
            if (candidates.any { matchesCandidate(node, it) }) {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { txt -> out.add(txt) }
            }
        }
        return out
    }

    /** targetText 와 정확히 일치하는 항목만 클릭한다(부분 문자열 클릭 금지, 지시서 7.2). */
    fun selectCategoryStep(targetText: String): ActionResult {
        if (identifyScreen() != ScreenId.CATEGORY_DIALOG) return ActionResult.Retryable("게시판 목록 화면 아님")
        val root = rootInActiveWindow ?: return ActionResult.Retryable("화면 정보를 읽지 못함")
        var node: AccessibilityNodeInfo? = null
        traverse(root) { n ->
            if (node == null && (n.text?.toString() ?: "") == targetText) node = n
        }
        val target = node ?: return ActionResult.Retryable("게시판 항목을 찾지 못함")
        if (!clickNode(target)) return ActionResult.Retryable("게시판 항목 클릭 실패")

        val rules = ensureRules()
        val confirmCandidates = rules.screen("CATEGORY_DIALOG").safeConfirmButton
        if (confirmCandidates.isNotEmpty()) {
            findFirst(confirmCandidates)?.let { confirmNode ->
                val result = safeClick(confirmNode, "CATEGORY_DIALOG")
                if (result is ActionResult.Blocked) return result
            }
        }
        return ActionResult.Success("게시판 선택 클릭 완료")
    }

    fun verifyCategorySelectedStep(expectedText: String): ActionResult {
        if (identifyScreen() != ScreenId.WRITE_EDITOR) return ActionResult.Retryable("글쓰기 화면 복귀 확인 필요")
        val rules = ensureRules()
        val btn = findFirst(rules.screen("WRITE_EDITOR").categoryButton)
        val shown = btn?.text?.toString() ?: ""
        return if (shown.contains(expectedText)) ActionResult.Success("게시판 표시 확인됨")
        else ActionResult.Retryable("게시판 표시가 아직 반영되지 않음")
    }

    /** 게시판 자동 매칭 실패 후 사용자가 직접 선택한 게시판명을 화면에서 읽는다(작업지시서 7.2). */
    fun readCategoryDisplayStep(): String? {
        if (identifyScreen() != ScreenId.WRITE_EDITOR) return null
        val rules = ensureRules()
        val btn = findFirst(rules.screen("WRITE_EDITOR").categoryButton) ?: return null
        return btn.text?.toString()?.takeIf { it.isNotBlank() }
    }

    // ---------- 제목/본문 자동 입력 (지시서 6.1/6.2, 선택자 기반) ----------

    fun inputTitleStep(context: Context): ActionResult {
        val data = SessionRepository.postData ?: return ActionResult.Blocked("불러온 자료 없음")
        if (identifyScreen() != ScreenId.WRITE_EDITOR) return ActionResult.Retryable("글쓰기 화면 아님")
        val rules = ensureRules()
        val field = findFirst(rules.screen("WRITE_EDITOR").titleField)
            ?: run {
                ClipboardInputHelper.copyWithAutoClear(context, "제목", data.title, FALLBACK_CLEAR_DELAY)
                return ActionResult.NeedsUser("제목 입력칸을 찾지 못해 클립보드에 복사했습니다.")
            }
        if ((field.text?.toString() ?: "") == data.title) return ActionResult.Success("제목 이미 입력됨(중복 입력 방지)")
        val typed = setNodeText(field, data.title)
        val done = typed || pasteToNode(field)
        return if (done) ActionResult.Retryable("제목 입력 시도 · 확인 필요")
        else {
            ClipboardInputHelper.copyWithAutoClear(context, "제목", data.title, FALLBACK_CLEAR_DELAY)
            ActionResult.NeedsUser("제목 자동 입력 실패 · 클립보드에 복사했습니다.")
        }
    }

    fun verifyTitleStep(): ActionResult {
        val data = SessionRepository.postData ?: return ActionResult.Blocked("불러온 자료 없음")
        val rules = ensureRules()
        val field = findFirst(rules.screen("WRITE_EDITOR").titleField)
            ?: return ActionResult.Retryable("제목 입력칸 재확인 필요")
        val shown = field.text?.toString() ?: ""
        return if (shown == data.title) ActionResult.Success("제목 입력 확인됨")
        else ActionResult.Retryable("제목이 아직 반영되지 않음")
    }

    fun inputBodyStep(context: Context): ActionResult {
        val data = SessionRepository.postData ?: return ActionResult.Blocked("불러온 자료 없음")
        if (identifyScreen() != ScreenId.WRITE_EDITOR) return ActionResult.Retryable("글쓰기 화면 아님")
        val rules = ensureRules()
        val field = findFirst(rules.screen("WRITE_EDITOR").bodyField)
            ?: run {
                ClipboardInputHelper.copyWithAutoClear(context, "본문", data.body, FALLBACK_CLEAR_DELAY)
                return ActionResult.NeedsUser("본문 입력 영역을 찾지 못해 클립보드에 복사했습니다.")
            }
        val existing = field.text?.toString() ?: ""
        if (existing.isNotEmpty() && bodyLooksAlreadyPasted(existing, data.body)) {
            return ActionResult.Success("본문 이미 입력됨(중복 붙여넣기 방지)")
        }
        ClipboardInputHelper.copy(context, "본문", data.body)
        return if (pasteToNode(field)) {
            ClipboardInputHelper.scheduleClear(context, data.body, SUCCESS_CLEAR_DELAY)
            ActionResult.Retryable("본문 붙여넣기 시도 · 길이 확인 필요")
        } else {
            ClipboardInputHelper.scheduleClear(context, data.body, FALLBACK_CLEAR_DELAY)
            ActionResult.NeedsUser("본문 자동 입력 실패 · 클립보드에 복사했습니다.")
        }
    }

    private fun bodyLooksAlreadyPasted(existing: String, target: String): Boolean {
        if (target.isEmpty()) return false
        if (existing.length < (target.length * 0.9).toInt()) return false
        val prefixLen = minOf(30, target.length)
        return existing.take(prefixLen) == target.take(prefixLen)
    }

    fun verifyBodyStep(): ActionResult {
        val data = SessionRepository.postData ?: return ActionResult.Blocked("불러온 자료 없음")
        val rules = ensureRules()
        val field = findFirst(rules.screen("WRITE_EDITOR").bodyField)
            ?: return ActionResult.Retryable("본문 입력 영역 재확인 필요")
        val shown = field.text?.toString() ?: ""
        return if (bodyLooksAlreadyPasted(shown, data.body)) ActionResult.Success("본문 입력 확인됨(길이/지문 일치)")
        else ActionResult.Retryable("본문이 아직 반영되지 않음")
    }

    // ---------- 태그 1개씩 자동 입력 (지시서 6.3, 오케스트레이터가 반복 호출) ----------

    /** 태그 입력 영역을 준비한다(있으면 태그 버튼을 눌러 포커스, 없어도 다음 단계에서 재탐색). */
    fun openTagFieldStep(): ActionResult {
        if (identifyScreen() != ScreenId.WRITE_EDITOR) return ActionResult.Retryable("글쓰기 화면 아님")
        val rules = ensureRules()
        findFirst(rules.screen("WRITE_EDITOR").tagButton)?.let { clickNode(it) }
        return ActionResult.Success("태그 입력 영역 준비")
    }

    /** 태그 1개를 입력·Enter·확정 확인까지 수행한다. 이미 완료됐으면 Success. */
    fun inputSingleTagStep(context: Context): ActionResult {
        val controller = SessionRepository.tagController
        if (controller.isEmpty() || !controller.hasNext()) {
            return ActionResult.Success("태그 입력 완료(${controller.progressText()})")
        }
        if (identifyScreen() != ScreenId.WRITE_EDITOR) return ActionResult.Retryable("글쓰기 화면 아님")
        val rules = ensureRules()
        val field = findFirst(rules.screen("WRITE_EDITOR").tagButton)
            ?: (focusedEditable() ?: if (isLikelyWriteScreen()) findTagField() else null)
        val tag = controller.current() ?: return ActionResult.Success("태그 입력 완료")
        if (field == null) {
            ClipboardInputHelper.copyWithAutoClear(context, "태그", tag, FALLBACK_CLEAR_DELAY)
            return ActionResult.NeedsUser("태그 입력칸을 찾지 못해 클립보드에 복사했습니다(태그 ${controller.progressText()}).")
        }
        val typed = setNodeText(field, tag)
        val entered = typed && imeEnter(field)
        return if (typed && entered) {
            controller.markCurrentDone()
            SessionRepository.notifyChanged()
            AutomationLogStore.add("태그 ${controller.progressText()} 입력 완료") // 태그 내용은 기록하지 않음
            ActionResult.Success("태그 ${controller.progressText()} 입력 완료")
        } else {
            ClipboardInputHelper.copyWithAutoClear(context, "태그", tag, FALLBACK_CLEAR_DELAY)
            ActionResult.Retryable("태그 입력/확정 실패(태그 ${controller.progressText()})")
        }
    }

    // ---------- 사진 자동 선택 (지시서 8) ----------

    fun openPhotoButtonStep(): ActionResult {
        if (identifyScreen() != ScreenId.WRITE_EDITOR) return ActionResult.Retryable("글쓰기 화면 아님")
        val rules = ensureRules()
        val btn = findFirst(rules.screen("WRITE_EDITOR").photoButton)
            ?: return ActionResult.Retryable("사진 버튼을 찾지 못함")
        if (hasForbiddenWordNear(btn)) return ActionResult.Blocked("사진 버튼 근처 위험 단어 감지")
        return if (clickNode(btn)) ActionResult.Retryable("사진 버튼 클릭 시도")
        else ActionResult.Retryable("사진 버튼 클릭 실패")
    }

    /**
     * 사진 선택기 패키지를 식별한다. selector_rules.json 의 PHOTO_PICKER.package_names 가
     * 비어 있으면(=실기에서 아직 확인 못함) 절대 임의로 진행하지 않고 사용자에게 안내한다
     * (작업지시서 8.5: 패키지 미확인 상태에서 모든 앱 허용 금지).
     */
    fun identifyPhotoPickerStep(): ActionResult {
        val rules = ensureRules()
        val pkg = currentPackage() ?: return ActionResult.Retryable("전면 화면 패키지를 확인하지 못함")
        val allowed = rules.screen("PHOTO_PICKER").packageNames
        return when {
            allowed.isEmpty() -> ActionResult.NeedsUser(
                "사진 선택기 패키지가 아직 selector_rules.json에 등록되지 않았습니다. " +
                    "GPT공작소 앨범에서 사진을 직접 선택해 주세요."
            )
            pkg in allowed -> ActionResult.Success("허용된 사진 선택기 확인됨")
            else -> ActionResult.NeedsUser("확인되지 않은 사진 선택기 화면입니다 · 직접 선택해 주세요.")
        }
    }

    fun openAlbumMenuStep(): ActionResult {
        val rules = ensureRules()
        val btn = findFirst(rules.screen("PHOTO_PICKER").albumButton)
            ?: return ActionResult.Retryable("앨범 메뉴 버튼을 찾지 못함")
        return if (clickNode(btn)) ActionResult.Retryable("앨범 메뉴 클릭 시도")
        else ActionResult.Retryable("앨범 메뉴 클릭 실패")
    }

    fun selectGptAlbumStep(): ActionResult {
        val rules = ensureRules()
        val item = findFirst(rules.screen("PHOTO_PICKER").gptAlbumItem)
            ?: return ActionResult.Retryable("GPT공작소 앨범을 찾지 못함")
        return if (clickNode(item)) ActionResult.Retryable("GPT공작소 앨범 진입 시도")
        else ActionResult.Retryable("GPT공작소 앨범 클릭 실패")
    }

    /**
     * 사진 그리드에서 아직 선택하지 않은 항목 1개를 선택한다. GPT공작소 전용 앨범에
     * 진입한 뒤이므로 그리드 맨 앞(선택 개수 인덱스)이 현재 세션 이미지라는 전제를 쓰되,
     * 그리드 자체를 찾지 못하면 절대 추측 클릭하지 않고 사용자에게 안내한다(지시서 8.3).
     * 반환하는 Success 는 "사진 1장 클릭 성공"을 의미하며, 전체 완료 여부는
     * 오케스트레이터가 selectedSoFar/expectedTotal 카운트로 판단한다.
     */
    fun selectNextPhotoStep(expectedTotal: Int, selectedSoFar: Int): ActionResult {
        if (selectedSoFar >= expectedTotal) return ActionResult.Success("이미 선택 완료($selectedSoFar/$expectedTotal)")
        val rules = ensureRules()
        val grid = findAll(rules.screen("PHOTO_PICKER").photoGridItems)
        if (grid.isEmpty()) return ActionResult.Retryable("사진 그리드를 찾지 못함")
        val target = grid.getOrNull(selectedSoFar)
            ?: return ActionResult.NeedsUser(
                "현재 작업 사진을 정확히 구분하지 못했습니다. GPT공작소 앨범에서 표시된 사진을 직접 선택해 주세요."
            )
        return if (clickNode(target)) ActionResult.Success("사진 ${selectedSoFar + 1}/$expectedTotal 선택 시도됨")
        else ActionResult.Retryable("사진 선택 클릭 실패")
    }

    /** 선택기 화면에 표시된 "선택됨 N" 카운터를 읽는다(있으면). 없으면 null. */
    fun readSelectedCountStep(): Int? {
        val rules = ensureRules()
        val node = findFirst(rules.screen("PHOTO_PICKER").selectedCount) ?: return null
        val text = node.text?.toString() ?: return null
        return Regex("\\d+").find(text)?.value?.toIntOrNull()
    }

    fun confirmPhotoSelectionStep(): ActionResult {
        val rules = ensureRules()
        val btn = findFirst(rules.screen("PHOTO_PICKER").safeDoneButton)
            ?: return ActionResult.Retryable("완료 버튼을 찾지 못함")
        return safeClick(btn, "PHOTO_PICKER")
    }

    fun verifyPhotoAttachedStep(): ActionResult =
        if (identifyScreen() == ScreenId.WRITE_EDITOR) ActionResult.Success("글쓰기 화면 복귀 확인됨")
        else ActionResult.Retryable("글쓰기 화면 복귀 확인 필요")
}
