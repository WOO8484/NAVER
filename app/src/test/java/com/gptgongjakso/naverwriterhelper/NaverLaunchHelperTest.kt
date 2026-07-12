package com.gptgongjakso.naverwriterhelper

import com.gptgongjakso.naverwriterhelper.helper.NaverLaunchHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NaverLaunchHelper 단위 테스트 (작업지시서 7.1).
 *
 * Context/PackageManager 가 필요한 부분(isBlogAppInstalled, openNaverBlogApp 등)은
 * 순수 JVM 단위 테스트로 검증할 수 없으므로, 설치 여부 판정 로직(decideByInstallState)과
 * Play 스토어 실행 로직(openPlayStoreForBlogAppWith)을 Context 없이 테스트 가능한
 * 순수 함수로 분리해 검증한다.
 */
class NaverLaunchHelperTest {

    // 1) 블로그 앱 설치됨 → 블로그 앱 실행 결과
    @Test
    fun `블로그 앱 설치됨이면 OPENED_BLOG_APP`() {
        assertEquals(
            NaverLaunchHelper.LaunchResult.OPENED_BLOG_APP,
            NaverLaunchHelper.decideByInstallState(blogAppInstalled = true)
        )
    }

    // 2) 블로그 앱 미설치 → BLOG_APP_NOT_INSTALLED
    @Test
    fun `블로그 앱 미설치면 BLOG_APP_NOT_INSTALLED`() {
        assertEquals(
            NaverLaunchHelper.LaunchResult.BLOG_APP_NOT_INSTALLED,
            NaverLaunchHelper.decideByInstallState(blogAppInstalled = false)
        )
    }

    // 3) 일반 네이버 앱만 설치됨 → 일반 네이버 앱을 열지 않음
    //    decideByInstallState 는 블로그 앱 설치 여부만 입력받고, LaunchResult 에는
    //    일반 네이버 앱을 여는 결과값 자체가 존재하지 않는다(구조적으로 불가능).
    //    "일반 네이버 앱만 설치된 상태"는 blogAppInstalled=false 로 표현되며
    //    결과는 오직 BLOG_APP_NOT_INSTALLED 뿐이다.
    @Test
    fun `일반 네이버 앱만 설치된 상태(블로그 앱 미설치)에서도 블로그 앱 미설치 결과만 반환한다`() {
        val result = NaverLaunchHelper.decideByInstallState(blogAppInstalled = false)
        assertEquals(NaverLaunchHelper.LaunchResult.BLOG_APP_NOT_INSTALLED, result)
        // 일반 네이버 앱을 열었다는 결과값 자체가 enum 에 없음을 재확인
        val resultNames = NaverLaunchHelper.LaunchResult.entries.map { it.name }
        assertFalse(resultNames.any { it.contains("NAVER_APP") })
    }

    // 4) 브라우저만 사용 가능 → 모바일 블로그 홈을 열지 않음
    //    LaunchResult 에 브라우저를 여는 결과값 자체가 존재하지 않는다(구조적으로 불가능).
    @Test
    fun `브라우저 fallback 결과값은 존재하지 않는다`() {
        val resultNames = NaverLaunchHelper.LaunchResult.entries.map { it.name }
        assertFalse(resultNames.any { it.contains("BROWSER") })
        assertEquals(
            setOf("OPENED_BLOG_APP", "BLOG_APP_NOT_INSTALLED", "FAILED"),
            resultNames.toSet()
        )
    }

    // 5) Play 스토어 실행 실패 → 예외 없이 실패 결과 반환
    @Test
    fun `market과 web 실행이 모두 예외를 던져도 예외 없이 false를 반환한다`() {
        val result = NaverLaunchHelper.openPlayStoreForBlogAppWith(
            launchMarket = { throw RuntimeException("market intent 실패") },
            launchWeb = { throw RuntimeException("web intent 실패") }
        )
        assertFalse(result)
    }

    @Test
    fun `market 실행 실패시 web fallback이 성공하면 true를 반환한다`() {
        val result = NaverLaunchHelper.openPlayStoreForBlogAppWith(
            launchMarket = { throw RuntimeException("market intent 실패") },
            launchWeb = { true }
        )
        assertTrue(result)
    }

    @Test
    fun `market 실행이 성공하면 web fallback을 시도하지 않고 true를 반환한다`() {
        var webCalled = false
        val result = NaverLaunchHelper.openPlayStoreForBlogAppWith(
            launchMarket = { true },
            launchWeb = { webCalled = true; true }
        )
        assertTrue(result)
        assertFalse(webCalled)
    }

    @Test
    fun `market 딥링크와 web url이 블로그 앱 패키지를 가리킨다`() {
        assertTrue(NaverLaunchHelper.marketUri().contains(NaverLaunchHelper.PKG_BLOG))
        assertTrue(NaverLaunchHelper.marketUri().startsWith("market://details"))
        assertTrue(NaverLaunchHelper.playWebUrl().contains(NaverLaunchHelper.PKG_BLOG))
        assertTrue(NaverLaunchHelper.playWebUrl().startsWith("https://play.google.com"))
    }
}
