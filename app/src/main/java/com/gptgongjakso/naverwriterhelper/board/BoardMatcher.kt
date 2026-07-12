package com.gptgongjakso.naverwriterhelper.board

import com.gptgongjakso.naverwriterhelper.model.BoardProfile

/**
 * naver_category → 게시판 프로필 매칭 (지시서 10, v1.1.0에서 7장 확장).
 *
 * 규칙:
 *  1) 표시 이름 정확 일치 우선
 *  2) 별칭(aliases) 일치
 *  3) 찾지 못하면 null → 임의 선택 금지(사용자 선택 유도)
 *
 * 비교는 공백 제거 + 소문자로 관대하게 처리하되, 부분일치는 하지 않는다(오선택 방지).
 *
 * v1.1.0: [normalize] 를 공개해 실제 네이버 게시판 목록 화면에서 수집한 텍스트와도
 * 동일한 정규화 기준으로 비교할 수 있게 하고(작업지시서 7.1/12: BoardMatcher/BoardProfileRepository —
 * "공통 정규화 함수 분리"), [matchAgainstDisplayedTexts] 로 실제 화면 텍스트 목록에서
 * naver_category 정확 일치 → 별칭 정확 일치 순으로 클릭할 항목을 고른다(작업지시서 7.2).
 * 순수 로직 → 단위 테스트 가능.
 */
object BoardMatcher {

    fun match(category: String?, profiles: List<BoardProfile>): BoardProfile? {
        if (category.isNullOrBlank()) return null
        val key = normalize(category)

        // 1) 정확 일치
        profiles.firstOrNull { normalize(it.displayName) == key }?.let { return it }

        // 2) 별칭 일치
        profiles.firstOrNull { p -> p.aliases.any { normalize(it) == key } }?.let { return it }

        // 3) 없음
        return null
    }

    /** 공백 제거 + 소문자화. 게시판 프로필 비교와 실제 화면 텍스트 비교에 공통으로 쓰는 정규화. */
    fun normalize(s: String): String =
        s.trim().replace(Regex("\\s+"), "").lowercase()

    /**
     * 실제 네이버 게시판 목록 화면에서 수집한 텍스트들(displayedTexts) 중에서
     * naver_category 와 정확히 일치하는 항목을 우선 찾고, 없으면 [profile] 의 별칭과
     * 정확히 일치하는 항목을 찾는다. 부분 문자열만으로는 절대 선택하지 않는다(작업지시서 7.2).
     *
     * @return 클릭해야 할 실제 화면 텍스트(찾지 못하면 null)
     */
    fun matchAgainstDisplayedTexts(
        naverCategory: String?,
        displayedTexts: List<String>,
        profile: BoardProfile?
    ): String? {
        if (naverCategory.isNullOrBlank()) return null
        val key = normalize(naverCategory)

        // 1) naver_category 정확 일치
        displayedTexts.firstOrNull { normalize(it) == key }?.let { return it }

        // 2) 게시판 프로필 별칭과 정확 일치
        if (profile != null) {
            val aliasKeys = profile.aliases.map { normalize(it) }.toSet()
            displayedTexts.firstOrNull { normalize(it) in aliasKeys }?.let { return it }
        }

        // 3) 없음 — 임의 선택 금지
        return null
    }
}
