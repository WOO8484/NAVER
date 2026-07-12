package com.gptgongjakso.naverwriterhelper.automation

import com.gptgongjakso.naverwriterhelper.store.SessionRepository

/**
 * [AutomationStepExecutor] 의 테스트용 가짜 구현. 실제 접근성 서비스/네이버 앱 없이
 * [AutomationOrchestrator] 의 순서·재시도·타임아웃·일시정지 로직만 검증하기 위해 쓴다.
 * 각 필드를 원하는 [ActionResult] 로 바꿔 시나리오(성공/재시도초과/타임아웃/차단/사용자필요)를 구성한다.
 */
class FakeAutomationStepExecutor : AutomationStepExecutor {

    var openNaverResult: ActionResult = ActionResult.Success("네이버 실행됨")
    var openWriteScreenResult: ActionResult = ActionResult.Success("글쓰기 화면")
    var verifyWriteScreenResult: ActionResult = ActionResult.Success("글쓰기 화면 확인")
    var openCategoryResult: ActionResult = ActionResult.Success("카테고리 버튼")
    var categoryTexts: List<String> = emptyList()
    var selectCategoryResult: ActionResult = ActionResult.Success("게시판 클릭")
    var verifyCategoryResult: ActionResult = ActionResult.Success("게시판 확인")
    var displayedCategory: String? = null
    var inputTitleResult: ActionResult = ActionResult.Success("제목 입력")
    var verifyTitleResult: ActionResult = ActionResult.Success("제목 확인")
    var inputBodyResult: ActionResult = ActionResult.Success("본문 입력")
    var verifyBodyResult: ActionResult = ActionResult.Success("본문 확인")
    var openPhotoButtonResult: ActionResult = ActionResult.Success("사진 버튼")
    var identifyPhotoPickerResult: ActionResult = ActionResult.Success("선택기 확인")
    var openAlbumMenuResult: ActionResult = ActionResult.Success("앨범 메뉴")
    var selectGptAlbumResult: ActionResult = ActionResult.Success("GPT공작소 앨범")
    var selectNextPhotoResult: ActionResult = ActionResult.Success("사진 선택")
    var selectedPhotoCountToReport: Int? = 0
    var confirmPhotoResult: ActionResult = ActionResult.Success("완료 버튼")
    var verifyPhotoAttachResult: ActionResult = ActionResult.Success("첨부 확인")
    var openTagFieldResult: ActionResult = ActionResult.Success("태그 영역")
    var tagInputShouldSucceed: Boolean = true

    val callLog = mutableListOf<String>()

    override fun openNaverBlogApp(): ActionResult = openNaverResult.also { callLog += "openNaverBlogApp" }
    override fun openWriteScreen(): ActionResult = openWriteScreenResult.also { callLog += "openWriteScreen" }
    override fun verifyWriteScreen(): ActionResult = verifyWriteScreenResult.also { callLog += "verifyWriteScreen" }

    override fun openCategory(): ActionResult = openCategoryResult.also { callLog += "openCategory" }
    override fun collectCategoryTexts(): List<String> = categoryTexts.also { callLog += "collectCategoryTexts" }
    override fun selectCategory(targetText: String): ActionResult =
        selectCategoryResult.also { callLog += "selectCategory:$targetText" }
    override fun verifyCategorySelected(expectedText: String): ActionResult =
        verifyCategoryResult.also { callLog += "verifyCategorySelected:$expectedText" }
    override fun readDisplayedCategory(): String? = displayedCategory

    override fun inputTitle(): ActionResult = inputTitleResult.also { callLog += "inputTitle" }
    override fun verifyTitle(): ActionResult = verifyTitleResult.also { callLog += "verifyTitle" }
    override fun inputBody(): ActionResult = inputBodyResult.also { callLog += "inputBody" }
    override fun verifyBody(): ActionResult = verifyBodyResult.also { callLog += "verifyBody" }

    override fun openPhotoButton(): ActionResult = openPhotoButtonResult.also { callLog += "openPhotoButton" }
    override fun identifyPhotoPicker(): ActionResult = identifyPhotoPickerResult.also { callLog += "identifyPhotoPicker" }
    override fun openAlbumMenu(): ActionResult = openAlbumMenuResult.also { callLog += "openAlbumMenu" }
    override fun selectGptAlbum(): ActionResult = selectGptAlbumResult.also { callLog += "selectGptAlbum" }
    override fun selectNextPhoto(expectedTotal: Int, selectedSoFar: Int): ActionResult {
        callLog += "selectNextPhoto:$selectedSoFar/$expectedTotal"
        return selectNextPhotoResult
    }
    override fun readSelectedPhotoCount(): Int? = selectedPhotoCountToReport
    override fun confirmPhotoSelection(): ActionResult = confirmPhotoResult.also { callLog += "confirmPhotoSelection" }
    override fun verifyPhotoAttached(): ActionResult = verifyPhotoAttachResult.also { callLog += "verifyPhotoAttached" }

    override fun openTagField(): ActionResult = openTagFieldResult.also { callLog += "openTagField" }
    override fun inputSingleTag(): ActionResult {
        callLog += "inputSingleTag"
        val controller = SessionRepository.tagController
        if (!controller.hasNext()) return ActionResult.Success("태그 전체 완료")
        if (!tagInputShouldSucceed) return ActionResult.Retryable("태그 입력 실패(가짜)")
        controller.markCurrentDone()
        return ActionResult.Success("태그 1개 완료")
    }
}
