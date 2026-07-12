package com.gptgongjakso.naverwriterhelper.model

/**
 * 게시판 프로필 (지시서 10). v1.0.0 신규.
 *
 * 게시판명·별칭·허용/금지 주제·태그수·이미지 범위·본문 길이·중복 기간·하루 한도 등을 담는다.
 * 이미지 장수는 게시판별로 최소·권장·최대를 가진다(지시서 7: 5장 고정 금지).
 */
data class BoardProfile(
    /** 내부 식별 키 (예: "government_support") */
    val key: String,
    /** 게시판 표시 이름 (예: "정부지원") — naver_category 정확 일치 대상 */
    val displayName: String,
    /** 별칭 목록 (naver_category 별칭 매핑용) */
    val aliases: List<String> = emptyList(),
    /** 허용 주제 키워드(비어있으면 제한 없음) */
    val allowedTopics: List<String> = emptyList(),
    /** 금지 주제 키워드 */
    val forbiddenTopics: List<String> = emptyList(),
    /** 태그 최소/최대 개수 */
    val tagMin: Int = 1,
    val tagMax: Int = 30,
    /** 이미지 최소/권장/최대 (지시서 7 가변 지원) */
    val imageMin: Int = 1,
    val imageRecommended: Int = 5,
    val imageMax: Int = 10,
    /** 본문 최소/최대 길이(글자) */
    val bodyMinLength: Int = 0,
    val bodyMaxLength: Int = 100_000,
    /** 중복 판정 기간(일) — 이 기간 내 동일/유사 글은 주의/중복 */
    val duplicateWindowDays: Int = 30,
    /** 하루 발행 한도 */
    val dailyLimit: Int = 5
) {
    /** 이미지 장수가 이 게시판 규칙상 정상 범위인지 */
    fun isImageCountNormal(count: Int): Boolean = count in imageMin..imageMax

    /** 태그 개수가 이 게시판 규칙상 정상 범위인지 */
    fun isTagCountNormal(count: Int): Boolean = count in tagMin..tagMax
}
