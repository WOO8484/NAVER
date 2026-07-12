package com.gptgongjakso.naverwriterhelper.store

import com.gptgongjakso.naverwriterhelper.automation.AutomationOrchestrator
import com.gptgongjakso.naverwriterhelper.board.BoardMatcher
import com.gptgongjakso.naverwriterhelper.board.BoardProfileRepository
import com.gptgongjakso.naverwriterhelper.image.SavedImageEntry
import com.gptgongjakso.naverwriterhelper.model.BoardProfile
import com.gptgongjakso.naverwriterhelper.model.DuplicateCheckResult
import com.gptgongjakso.naverwriterhelper.model.NaverPostData
import com.gptgongjakso.naverwriterhelper.service.TagInputController
import com.gptgongjakso.naverwriterhelper.statemachine.PipelineStateMachine
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 앱 전역 세션 상태. v0.1.1 이식 + v1.0.0/v1.1.0 확장.
 * MainActivity / FloatingControlService / NaverAccessibilityService / AutomationOrchestrator 가 공유한다.
 * (외부 전송 없음. 순수 메모리 보관 + [com.gptgongjakso.naverwriterhelper.automation.AutomationSessionStore] 영구 저장)
 *
 * v1.1.0 확장(작업지시서 5, 14.1-24):
 *  - sessionId: 세션별 고유 ID(이미지 파일명·manifest·직렬화 키)
 *  - orchestrator: 현재 자동화 오케스트레이터 인스턴스(단일 세션 원칙)
 *  - imageManifest: 저장된 이미지 관리 정보(사진 자동 선택 계획에 사용)
 *  - manualCategorySelection: 게시판 자동 매칭 실패 후 사용자가 직접 선택한 게시판명
 *  - testMode: 시험 모드 여부(발행/이력 기록 없이 선택자·흐름만 확인)
 */
object SessionRepository {

    @Volatile
    var postData: NaverPostData? = null
        private set

    /** 태그 1개씩 입력 진행 상태 (v0.1.1) */
    val tagController = TagInputController()

    /** 처리 상태머신 (v1.0.0, v1.1.0 확장) */
    @Volatile
    var pipeline = PipelineStateMachine()
        private set

    /** 자동 매칭된 게시판 프로필(없으면 사용자 선택 필요) */
    @Volatile
    var selectedBoard: BoardProfile? = null

    /** 게시판 자동 매칭 실패 후 사용자가 네이버 화면에서 직접 선택한 게시판명(영구 별칭 저장은 하지 않음, 작업지시서 7.2) */
    @Volatile
    var manualCategorySelection: String? = null

    /** 최근 중복 검사 결과 */
    @Volatile
    var lastDuplicateResult: DuplicateCheckResult? = null

    /** 현재 자료의 자동화 세션 ID(이미지 파일명/직렬화 키에 사용) */
    @Volatile
    var sessionId: String = newSessionId()
        private set

    /** 저장된 이미지 관리 정보(사진 자동 선택 계획에 사용, 작업지시서 8.2) */
    @Volatile
    var imageManifest: List<SavedImageEntry> = emptyList()

    /** 시험 모드 여부(작업지시서 11) */
    @Volatile
    var testMode: Boolean = false

    /** 현재 실행 중인 자동화 오케스트레이터(없으면 자동화 미실행) */
    @Volatile
    var orchestrator: AutomationOrchestrator? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private fun newSessionId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    fun setPostData(data: NaverPostData) {
        postData = data
        tagController.reset(data.tags)
        // 새 자료 = 상태머신/세션 초기화
        pipeline = PipelineStateMachine()
        pipeline.dataId = data.zipSha256.take(12)
        sessionId = newSessionId()
        imageManifest = emptyList()
        manualCategorySelection = null
        orchestrator = null
        // 게시판 자동 매칭(임의 선택 아님: 실패 시 null)
        selectedBoard = BoardMatcher.match(data.metadata.naverCategory, BoardProfileRepository.defaults)
        lastDuplicateResult = null
        notifyChanged()
    }

    fun clear() {
        postData = null
        tagController.reset(emptyList())
        pipeline = PipelineStateMachine()
        selectedBoard = null
        manualCategorySelection = null
        lastDuplicateResult = null
        imageManifest = emptyList()
        orchestrator = null
        sessionId = newSessionId()
        notifyChanged()
    }

    fun hasData(): Boolean = postData != null

    /** 적용할 게시판(매칭 실패 시 일반 프로필로 검증 기준만 제공) */
    fun effectiveBoard(): BoardProfile = selectedBoard ?: BoardProfileRepository.fallback

    /** naver_category 원문(게시판 실제 화면 매칭에 사용) */
    fun naverCategory(): String? = postData?.metadata?.naverCategory

    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }
}
