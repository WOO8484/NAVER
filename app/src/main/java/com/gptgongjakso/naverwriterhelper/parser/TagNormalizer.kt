package com.gptgongjakso.naverwriterhelper.parser

/**
 * 태그 정규화 로직 (이 앱의 핵심). v0.1.1 검증 로직 그대로 이식(규칙 변경 없음).
 *
 * 어떤 형태로 들어오든 아래 규칙에 따라 "태그 1개씩" 배열로 변환한다.
 *  1. # 제거 후 내부 값만 보관
 *  2. 줄바꿈 / 쉼표 / 공백 / # 를 모두 구분자로 처리
 *  3. 빈 값 제거
 *  4. 중복 제거 (첫 등장 순서 유지)
 *  5. 특수문자 제거 (한글/영문/숫자/밑줄만 허용)
 *  6. 너무 긴(붙어버린) 태그 방지
 *  7. "정보 없음", "없음", "태그 없음" 같은 placeholder 제거
 *  8. 저장은 순수 텍스트(#없이). 실제 입력 시 네이버 방식에 맞춰 1개씩 입력.
 *
 * 이 클래스는 Android 의존성이 전혀 없는 순수 로직이라 단위 테스트가 쉽다.
 */
object TagNormalizer {

    /** 한 개 태그로 허용하는 최대 길이. 이보다 길면 붙어버린/쓰레기 값으로 보고 버린다. */
    private const val MAX_TAG_LENGTH = 30

    /** 구분자: 공백/탭/줄바꿈, 쉼표(반각/전각), # */
    private val DELIMITER_REGEX = Regex("[\\s,\\uFF0C#]+")

    /** 허용하지 않는 문자(특수문자/이모지 등) 제거용. 한글/영문/숫자/밑줄만 남긴다. */
    private val DISALLOWED_CHAR_REGEX = Regex("[^\\p{L}\\p{N}_]")

    /** 입력 전체가 이 값들과 같으면(공백 무시) 태그 없음으로 처리. */
    private val WHOLE_PLACEHOLDERS = setOf(
        "정보없음", "태그없음", "해시태그없음", "없음", "없습니다",
        "na", "n/a", "none", "null", "-", "빈값"
    )

    /** 개별 토큰이 이 값들과 같으면 버린다. */
    private val TOKEN_PLACEHOLDERS = setOf(
        "정보없음", "태그없음", "해시태그없음", "없음", "없습니다",
        "na", "none", "null", "빈값"
    )

    /**
     * 원본 태그 문자열을 정규화된 태그 리스트로 변환한다.
     * @return #이 제거된 순수 태그 텍스트 리스트 (순서/중복 정리 완료)
     */
    fun normalize(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()

        // (7) 입력 전체가 placeholder면 즉시 빈 리스트
        val wholeKey = raw.replace(Regex("[\\s#/]+"), "").lowercase()
        if (wholeKey in WHOLE_PLACEHOLDERS) return emptyList()

        val result = LinkedHashSet<String>() // (4) 순서 유지 + 중복 제거

        for (rawToken in raw.split(DELIMITER_REGEX)) {
            // (1)(5) # 는 이미 구분자로 처리됨. 남은 특수문자 제거.
            val cleaned = DISALLOWED_CHAR_REGEX.replace(rawToken, "").trim()

            // (3) 빈 값 제거
            if (cleaned.isEmpty()) continue

            // (7) 개별 placeholder 제거
            if (cleaned.lowercase() in TOKEN_PLACEHOLDERS) continue

            // (6) 너무 긴(붙어버린) 태그 방지
            if (cleaned.length > MAX_TAG_LENGTH) continue

            result.add(cleaned)
        }

        return result.toList()
    }

    /**
     * 화면 표시용: 각 태그 앞에 # 를 붙여 문자열로 만든다.
     * 예) [폭염특보, 장마특보] -> "#폭염특보 #장마특보"
     */
    fun toDisplayString(tags: List<String>): String =
        tags.joinToString(" ") { "#$it" }
}
