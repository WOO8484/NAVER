package com.gptgongjakso.naverwriterhelper.automation

import android.content.Context
import com.gptgongjakso.naverwriterhelper.helper.NaverLaunchHelper
import com.gptgongjakso.naverwriterhelper.service.NaverAccessibilityService

/**
 * [AutomationStepExecutor] 의 실제 Android 구현. v1.1.0 신규.
 * NaverAccessibilityService 인스턴스와 NaverLaunchHelper 를 통해 실제 화면을 조작한다.
 * 접근성 서비스가 꺼져 있으면 모든 단계에서 안전하게 NeedsUser 를 반환한다.
 */
class AutomationStepExecutorImpl(private val context: Context) : AutomationStepExecutor {

    private fun svc(): NaverAccessibilityService? = NaverAccessibilityService.instance

    private fun needsAccessibility(): ActionResult =
        ActionResult.NeedsUser("접근성 서비스가 꺼져 있습니다. 설정에서 GPT 공작소 접근성을 켜 주세요.")

    override fun openNaverBlogApp(): ActionResult = when (NaverLaunchHelper.openNaverBlogApp(context)) {
        NaverLaunchHelper.LaunchResult.OPENED_BLOG_APP -> ActionResult.Success("네이버 블로그 앱 실행됨")
        NaverLaunchHelper.LaunchResult.BLOG_APP_NOT_INSTALLED ->
            ActionResult.NeedsUser("네이버 블로그 앱이 설치되어 있지 않습니다.")
        NaverLaunchHelper.LaunchResult.FAILED -> ActionResult.Retryable("네이버 블로그 앱 실행 실패")
    }

    override fun openWriteScreen(): ActionResult = svc()?.openWriteScreenStep() ?: needsAccessibility()
    override fun verifyWriteScreen(): ActionResult = svc()?.verifyWriteScreenStep() ?: needsAccessibility()

    override fun openCategory(): ActionResult = svc()?.openCategoryStep() ?: needsAccessibility()
    override fun collectCategoryTexts(): List<String> = svc()?.collectCategoryTexts() ?: emptyList()
    override fun selectCategory(targetText: String): ActionResult =
        svc()?.selectCategoryStep(targetText) ?: needsAccessibility()
    override fun verifyCategorySelected(expectedText: String): ActionResult =
        svc()?.verifyCategorySelectedStep(expectedText) ?: needsAccessibility()
    override fun readDisplayedCategory(): String? = svc()?.readCategoryDisplayStep()

    override fun inputTitle(): ActionResult = svc()?.inputTitleStep(context) ?: needsAccessibility()
    override fun verifyTitle(): ActionResult = svc()?.verifyTitleStep() ?: needsAccessibility()
    override fun inputBody(): ActionResult = svc()?.inputBodyStep(context) ?: needsAccessibility()
    override fun verifyBody(): ActionResult = svc()?.verifyBodyStep() ?: needsAccessibility()

    override fun openPhotoButton(): ActionResult = svc()?.openPhotoButtonStep() ?: needsAccessibility()
    override fun identifyPhotoPicker(): ActionResult = svc()?.identifyPhotoPickerStep() ?: needsAccessibility()
    override fun openAlbumMenu(): ActionResult = svc()?.openAlbumMenuStep() ?: needsAccessibility()
    override fun selectGptAlbum(): ActionResult = svc()?.selectGptAlbumStep() ?: needsAccessibility()
    override fun selectNextPhoto(expectedTotal: Int, selectedSoFar: Int): ActionResult =
        svc()?.selectNextPhotoStep(expectedTotal, selectedSoFar) ?: needsAccessibility()
    override fun readSelectedPhotoCount(): Int? = svc()?.readSelectedCountStep()
    override fun confirmPhotoSelection(): ActionResult = svc()?.confirmPhotoSelectionStep() ?: needsAccessibility()
    override fun verifyPhotoAttached(): ActionResult = svc()?.verifyPhotoAttachedStep() ?: needsAccessibility()

    override fun openTagField(): ActionResult = svc()?.openTagFieldStep() ?: needsAccessibility()
    override fun inputSingleTag(): ActionResult = svc()?.inputSingleTagStep(context) ?: needsAccessibility()
}
