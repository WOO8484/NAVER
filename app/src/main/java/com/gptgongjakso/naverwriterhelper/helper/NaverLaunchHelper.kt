package com.gptgongjakso.naverwriterhelper.helper

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 네이버 블로그 앱 실행 전용 헬퍼 (v1.0.1).
 *
 * 정책(작업지시서 3.1~3.2):
 *  - 네이버 블로그 앱(com.nhn.android.blog)만 실행한다.
 *  - 네이버 일반 앱(com.nhn.android.search) fallback 없음.
 *  - 모바일 블로그 브라우저 fallback 없음.
 *  - 미설치 시 Play 스토어 설치 화면으로 안내한다(market:// 우선, https 는 보조 fallback).
 *  - 글쓰기 화면 진입은 사용자가 앱 안에서 직접 누른다(임의 딥링크로 강제 호출하지 않음).
 *
 * 설치 여부 판정과 Intent 생성을 분리해 테스트 가능한 순수 함수로 노출한다.
 * ※ 이 앱은 네이버 로그인 정보를 저장/입력하지 않는다. 로그인은 사용자가 직접 한다.
 */
object NaverLaunchHelper {

    const val PKG_BLOG = "com.nhn.android.blog"

    enum class LaunchResult { OPENED_BLOG_APP, BLOG_APP_NOT_INSTALLED, FAILED }

    // ======================= 순수 로직 (Context 불필요 · 단위 테스트 가능) =======================

    /** market:// 딥링크 URI 문자열 */
    fun marketUri(): String = "market://details?id=$PKG_BLOG"

    /** market Intent 실패 시에만 사용하는 Play 웹 설치 페이지 URL */
    fun playWebUrl(): String = "https://play.google.com/store/apps/details?id=$PKG_BLOG"

    /**
     * 블로그 앱 설치 여부만으로 결과를 결정한다.
     * 네이버 일반 앱이나 브라우저 가용 여부는 입력값으로 받지 않는다.
     * → 구조적으로 이 함수는 블로그 앱 외 다른 경로를 열 수 없다.
     */
    fun decideByInstallState(blogAppInstalled: Boolean): LaunchResult =
        if (blogAppInstalled) LaunchResult.OPENED_BLOG_APP else LaunchResult.BLOG_APP_NOT_INSTALLED

    /**
     * Play 스토어 설치 화면 실행 순서 로직: market Intent 우선 → 실패 시 https 웹 fallback.
     * launchMarket/launchWeb 은 테스트에서 가짜 함수로 주입 가능하다.
     * 두 시도가 모두 실패해도 예외를 던지지 않고 false 를 반환한다.
     */
    fun openPlayStoreForBlogAppWith(
        launchMarket: () -> Boolean,
        launchWeb: () -> Boolean
    ): Boolean {
        val marketOk = runCatching { launchMarket() }.getOrDefault(false)
        if (marketOk) return true
        return runCatching { launchWeb() }.getOrDefault(false)
    }

    // ======================= Context 필요 (실제 Android 동작) =======================

    /** 네이버 블로그 앱 설치 여부 확인 */
    fun isBlogAppInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getLaunchIntentForPackage(PKG_BLOG) != null }
            .getOrDefault(false)

    /** 네이버 블로그 앱 실행. 설치 안 됨/실행 실패를 구분해 반환한다. */
    fun openNaverBlogApp(context: Context): LaunchResult {
        if (!isBlogAppInstalled(context)) return LaunchResult.BLOG_APP_NOT_INSTALLED
        val intent = context.packageManager.getLaunchIntentForPackage(PKG_BLOG)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: return LaunchResult.BLOG_APP_NOT_INSTALLED
        val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        return if (ok) LaunchResult.OPENED_BLOG_APP else LaunchResult.FAILED
    }

    /** Play 스토어에서 네이버 블로그 앱 설치 화면 열기. market:// 우선, 실패 시에만 https fallback. */
    fun openPlayStoreForBlogApp(context: Context): Boolean = openPlayStoreForBlogAppWith(
        launchMarket = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(marketUri()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        },
        launchWeb = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(playWebUrl()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }
    )
}
