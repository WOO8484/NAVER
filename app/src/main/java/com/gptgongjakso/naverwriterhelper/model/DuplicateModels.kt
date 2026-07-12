package com.gptgongjakso.naverwriterhelper.model

/**
 * 중복 검사 판정 (지시서 18). v1.0.0 신규.
 *  - NORMAL: 정상 (신규 글)
 *  - CAUTION: 주의 (유사 주제/키워드 최근 발행)
 *  - SUSPECT: 중복 의심 (본문 지문 유사)
 *  - IDENTICAL: 동일 글 (동일 ZIP/본문 해시) → 진행 차단 대상
 */
enum class DuplicateVerdict {
    NORMAL, CAUTION, SUSPECT, IDENTICAL;

    /** 사용자 확인 없이 자동 진행을 막아야 하는 판정인지 */
    val blocksAutoProceed: Boolean
        get() = this == IDENTICAL

    val label: String
        get() = when (this) {
            NORMAL -> "정상"
            CAUTION -> "주의"
            SUSPECT -> "중복 의심"
            IDENTICAL -> "동일 글"
        }
}

/**
 * 발행/작성 이력 레코드 (지시서 18 저장 항목).
 * DB 저장 및 중복 판정 입력으로 사용.
 */
data class PostHistoryRecord(
    val postId: String,
    val title: String,
    /** 원문(본문) — 로그가 아닌 DB 에만 보관 */
    val bodyText: String,
    val boardKey: String,
    val topicKey: String?,
    val topicAngle: String?,
    val tags: List<String>,
    /** 최초 저장 시각(epoch millis) */
    val createdAt: Long,
    /** 사용자 선택 발행 상태(지시서 19): 발행완료/임시저장/취소/실패/미확인 */
    val publishStatus: String,
    /** ZIP 원본 SHA-256 */
    val zipSha256: String,
    /** 본문 SHA-256 */
    val bodySha256: String,
    /** 본문 지문(정규화 후 해시) */
    val bodyFingerprint: String,
    /** 수정본 버전(content_version) */
    val contentVersion: Int,
    /** 마지막 검사 결과 */
    val lastVerdict: String
)

/** 중복 검사 결과 상세 */
data class DuplicateCheckResult(
    val verdict: DuplicateVerdict,
    /** 사람이 읽을 사유 */
    val reason: String,
    /** 매칭된 기존 레코드의 postId (있으면) */
    val matchedPostId: String? = null
)
