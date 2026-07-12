package com.gptgongjakso.naverwriterhelper.automation

/**
 * 오케스트레이터가 각 단계의 실제 동작(접근성 탐색/클릭/입력, 네이버 앱 실행 등)을
 * 요청하기 위한 인터페이스 (작업지시서 5). v1.1.0 신규.
 *
 * 실제 구현([AutomationStepExecutorImpl])은 NaverAccessibilityService/NaverLaunchHelper 를 쓰고,
 * 단위 테스트는 이 인터페이스의 가짜 구현을 주입해 Context/AccessibilityService 없이도
 * [AutomationOrchestrator] 의 순서·재시도·타임아웃·일시정지 로직만 검증한다.
 *
 * 한 함수 호출 = 화면 탐색 + 1개 동작(또는 순수 조회) 이다. 여러 단계를 이 인터페이스
 * 안에서 스스로 이어가지 않는다 — 그 책임은 오케스트레이터에 있다.
 */
interface AutomationStepExecutor {
    fun openNaverBlogApp(): ActionResult
    fun openWriteScreen(): ActionResult
    fun verifyWriteScreen(): ActionResult

    fun openCategory(): ActionResult
    fun collectCategoryTexts(): List<String>
    fun selectCategory(targetText: String): ActionResult
    fun verifyCategorySelected(expectedText: String): ActionResult
    /** 게시판 자동 매칭 실패 후 사용자가 직접 선택한 뒤, 현재 화면에 표시된 게시판명을 읽는다. */
    fun readDisplayedCategory(): String?

    fun inputTitle(): ActionResult
    fun verifyTitle(): ActionResult
    fun inputBody(): ActionResult
    fun verifyBody(): ActionResult

    fun openPhotoButton(): ActionResult
    fun identifyPhotoPicker(): ActionResult
    fun openAlbumMenu(): ActionResult
    fun selectGptAlbum(): ActionResult
    fun selectNextPhoto(expectedTotal: Int, selectedSoFar: Int): ActionResult
    fun readSelectedPhotoCount(): Int?
    fun confirmPhotoSelection(): ActionResult
    fun verifyPhotoAttached(): ActionResult

    fun openTagField(): ActionResult
    fun inputSingleTag(): ActionResult
}
