package com.gptgongjakso.naverwriterhelper.board

import com.gptgongjakso.naverwriterhelper.model.BoardProfile

/**
 * 게시판 프로필 저장소 (지시서 10: 9개 게시판 프로필). v1.0.0 신규.
 *
 * 기본 내장 프로필 9종을 제공한다. 각 프로필은 이미지 최소/권장/최대를 개별로 가진다(지시서 7).
 * (향후 사용자 편집/추가는 확장 지점으로 남겨두되, 기본값은 지시서 취지에 맞춰 보수적으로 설정)
 */
object BoardProfileRepository {

    val defaults: List<BoardProfile> = listOf(
        BoardProfile(
            key = "government_support",
            displayName = "정부지원",
            aliases = listOf("정부 지원", "정부지원금", "지원금", "government", "gov"),
            allowedTopics = listOf("에너지바우처", "지원금", "보조금", "복지", "신청"),
            tagMin = 3, tagMax = 15,
            imageMin = 1, imageRecommended = 5, imageMax = 10,
            bodyMinLength = 300, bodyMaxLength = 20_000,
            duplicateWindowDays = 30, dailyLimit = 3
        ),
        BoardProfile(
            key = "policy_news",
            displayName = "정책뉴스",
            aliases = listOf("정책 뉴스", "정책", "policy"),
            tagMin = 3, tagMax = 15,
            imageMin = 1, imageRecommended = 5, imageMax = 10,
            bodyMinLength = 300, bodyMaxLength = 20_000,
            duplicateWindowDays = 21, dailyLimit = 4
        ),
        BoardProfile(
            key = "living_info",
            displayName = "생활정보",
            aliases = listOf("생활 정보", "생활", "living", "info"),
            tagMin = 3, tagMax = 20,
            imageMin = 1, imageRecommended = 5, imageMax = 10,
            bodyMinLength = 200, bodyMaxLength = 15_000,
            duplicateWindowDays = 30, dailyLimit = 5
        ),
        BoardProfile(
            key = "weather_safety",
            displayName = "기상안전",
            aliases = listOf("기상 안전", "날씨", "기상", "weather", "safety"),
            allowedTopics = listOf("폭염", "장마", "한파", "태풍", "특보"),
            tagMin = 3, tagMax = 15,
            imageMin = 1, imageRecommended = 4, imageMax = 8,
            bodyMinLength = 150, bodyMaxLength = 12_000,
            duplicateWindowDays = 14, dailyLimit = 6
        ),
        BoardProfile(
            key = "health",
            displayName = "건강정보",
            aliases = listOf("건강 정보", "건강", "health"),
            forbiddenTopics = listOf("허위광고", "과장광고"),
            tagMin = 3, tagMax = 15,
            imageMin = 1, imageRecommended = 5, imageMax = 10,
            bodyMinLength = 300, bodyMaxLength = 18_000,
            duplicateWindowDays = 30, dailyLimit = 3
        ),
        BoardProfile(
            key = "finance",
            displayName = "금융정보",
            aliases = listOf("금융 정보", "금융", "재테크", "finance"),
            forbiddenTopics = listOf("투자권유", "리딩방", "원금보장"),
            tagMin = 3, tagMax = 15,
            imageMin = 1, imageRecommended = 5, imageMax = 10,
            bodyMinLength = 300, bodyMaxLength = 18_000,
            duplicateWindowDays = 30, dailyLimit = 3
        ),
        BoardProfile(
            key = "education",
            displayName = "교육정보",
            aliases = listOf("교육 정보", "교육", "입시", "education"),
            tagMin = 3, tagMax = 15,
            imageMin = 1, imageRecommended = 5, imageMax = 10,
            bodyMinLength = 300, bodyMaxLength = 18_000,
            duplicateWindowDays = 30, dailyLimit = 4
        ),
        BoardProfile(
            key = "local_event",
            displayName = "지역행사",
            aliases = listOf("지역 행사", "행사", "축제", "event", "local"),
            tagMin = 3, tagMax = 20,
            imageMin = 1, imageRecommended = 6, imageMax = 10,
            bodyMinLength = 150, bodyMaxLength = 12_000,
            duplicateWindowDays = 14, dailyLimit = 6
        ),
        BoardProfile(
            key = "general",
            displayName = "일반",
            aliases = listOf("기타", "general", "etc", "default"),
            tagMin = 1, tagMax = 30,
            imageMin = 1, imageRecommended = 5, imageMax = 10,
            bodyMinLength = 0, bodyMaxLength = 100_000,
            duplicateWindowDays = 30, dailyLimit = 10
        )
    )

    /** 매칭 실패 시 사용자에게 제시할 안전한 기본 프로필(임의 자동선택 아님, 검증 기준용) */
    val fallback: BoardProfile get() = defaults.first { it.key == "general" }

    fun byKey(key: String): BoardProfile? = defaults.firstOrNull { it.key == key }
}
