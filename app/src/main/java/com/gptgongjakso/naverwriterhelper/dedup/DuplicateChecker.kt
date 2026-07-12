package com.gptgongjakso.naverwriterhelper.dedup

import com.gptgongjakso.naverwriterhelper.model.DuplicateCheckResult
import com.gptgongjakso.naverwriterhelper.model.DuplicateVerdict
import com.gptgongjakso.naverwriterhelper.model.PostHistoryRecord

/**
 * 중복 글 방지 판정 (지시서 18). v1.0.0 신규.
 *
 * 판정 우선순위(높은 것 우선):
 *   1) IDENTICAL(동일 글): 동일 ZIP SHA-256 또는 동일 본문 SHA-256 이 존재 → 진행 차단 대상.
 *      (단, 같은 post_id 의 더 높은 content_version 이면서 본문이 다르면 '수정본'으로 보고 제외)
 *   2) SUSPECT(중복 의심): 본문 지문(fingerprint)이 같으나 정확한 본문 해시는 다름(사소한 편집).
 *   3) CAUTION(주의): 중복 판정 기간(windowDays) 내에 동일 주제키(+관점) 또는 동일 제목 발행 이력.
 *   4) NORMAL(정상): 위 어디에도 해당하지 않음.
 *
 * 순수 로직 → 단위 테스트 가능.
 */
object DuplicateChecker {

    /**
     * @param zipSha256 후보 글의 ZIP 해시
     * @param bodySha256 후보 글의 본문 해시
     * @param bodyFingerprint 후보 글의 본문 지문
     * @param title 후보 글 제목
     * @param topicKey 후보 글 주제키 (nullable)
     * @param topicAngle 후보 글 관점 (nullable)
     * @param contentVersion 후보 글 수정본 버전
     * @param postId 후보 글 post_id (수정본 판별용, nullable)
     * @param existing 기존 이력
     * @param nowMillis 현재 시각
     * @param windowDays 주의 판정 기간(일)
     */
    fun check(
        zipSha256: String,
        bodySha256: String,
        bodyFingerprint: String,
        title: String,
        topicKey: String?,
        topicAngle: String?,
        contentVersion: Int,
        postId: String?,
        existing: List<PostHistoryRecord>,
        nowMillis: Long,
        windowDays: Int
    ): DuplicateCheckResult {
        // 1) 동일 글 (해시 완전 일치)
        for (rec in existing) {
            val sameZip = zipSha256.isNotEmpty() && zipSha256 == rec.zipSha256
            val sameBody = bodySha256.isNotEmpty() && bodySha256 == rec.bodySha256
            if (sameZip || sameBody) {
                // 수정본 예외: post_id 동일 + 더 높은 content_version + 본문이 실제로 다름
                val isNewerRevision =
                    postId != null && postId == rec.postId &&
                        contentVersion > rec.contentVersion && !sameBody
                if (!isNewerRevision) {
                    return DuplicateCheckResult(
                        verdict = DuplicateVerdict.IDENTICAL,
                        reason = if (sameZip) "동일한 ZIP(원본)이 이미 처리되었습니다."
                        else "동일한 본문이 이미 처리되었습니다.",
                        matchedPostId = rec.postId
                    )
                }
            }
        }

        // 2) 중복 의심 (지문 일치, 본문 해시는 다름)
        for (rec in existing) {
            if (bodyFingerprint.isNotEmpty() && bodyFingerprint == rec.bodyFingerprint &&
                bodySha256 != rec.bodySha256
            ) {
                return DuplicateCheckResult(
                    verdict = DuplicateVerdict.SUSPECT,
                    reason = "본문이 기존 글과 거의 동일합니다(사소한 편집만 다름).",
                    matchedPostId = rec.postId
                )
            }
        }

        // 3) 주의 (기간 내 동일 주제/제목)
        val windowMillis = windowDays.toLong() * 24L * 60L * 60L * 1000L
        for (rec in existing) {
            val withinWindow = nowMillis - rec.createdAt <= windowMillis
            if (!withinWindow) continue

            val sameTopic = !topicKey.isNullOrBlank() && topicKey == rec.topicKey &&
                (topicAngle.isNullOrBlank() || topicAngle == rec.topicAngle)
            val sameTitle = title.isNotBlank() && title.trim() == rec.title.trim()

            if (sameTopic || sameTitle) {
                return DuplicateCheckResult(
                    verdict = DuplicateVerdict.CAUTION,
                    reason = if (sameTopic) "최근 ${windowDays}일 내 같은 주제로 발행한 이력이 있습니다."
                    else "최근 ${windowDays}일 내 같은 제목으로 발행한 이력이 있습니다.",
                    matchedPostId = rec.postId
                )
            }
        }

        return DuplicateCheckResult(DuplicateVerdict.NORMAL, "신규 글입니다.")
    }
}
