package com.gptgongjakso.naverwriterhelper

import android.app.Application
import com.gptgongjakso.naverwriterhelper.store.AutomationLogStore

/**
 * 앱 진입 Application. v1.0.0 신규.
 * 전역 로그 저장소를 초기화한다(영속 로그 복원).
 */
class GptGongjaksoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AutomationLogStore.init(applicationContext)
        AutomationLogStore.add("GPT 공작소 v1.1.0 시작")
    }
}
