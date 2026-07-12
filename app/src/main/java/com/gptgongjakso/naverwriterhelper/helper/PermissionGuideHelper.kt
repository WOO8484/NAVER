package com.gptgongjakso.naverwriterhelper.helper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.gptgongjakso.naverwriterhelper.service.NaverAccessibilityService

/**
 * 앱에 필요한 권한 상태 확인 + 관련 설정 화면 이동.
 * 권한이 없어도 앱은 멈추지 않고, 설정으로 이동하는 경로만 제공한다.
 */
object PermissionGuideHelper {

    /** 접근성 서비스가 켜져 있는지 */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${NaverAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = splitter.next()
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    /** 다른 앱 위에 표시(오버레이) 권한 */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 알림 권한 (Android 13+). 그 이하 버전은 항상 true */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 사진 저장 권한 상태.
     * API 29+ 는 MediaStore 저장에 별도 권한이 필요 없으므로 항상 준비됨.
     */
    fun isPhotoSaveReady(): Boolean = true

    // ---------- 설정 화면 이동 ----------

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                // 일부 기기에서 패키지 지정 실패 시 전체 목록으로
                val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(fallback) }
            }
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
