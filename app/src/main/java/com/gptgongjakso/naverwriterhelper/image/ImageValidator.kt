package com.gptgongjakso.naverwriterhelper.image

import com.gptgongjakso.naverwriterhelper.dedup.ContentFingerprint
import com.gptgongjakso.naverwriterhelper.model.BoardProfile
import com.gptgongjakso.naverwriterhelper.model.ImageRole
import com.gptgongjakso.naverwriterhelper.model.ParsedImage

/**
 * 이미지 수 가변 검사 (지시서 7). v1.0.0 신규.
 *
 * 검사 항목:
 *  - 장수: 게시판 프로필의 최소/권장/최대와 비교 (5장 고정 금지)
 *  - metadata.image_count 와 실제 장수 일치 여부
 *  - 대표 이미지(썸네일) 존재/개수
 *  - 본문 이미지 순서(중복 인덱스)
 *  - 내용 중복(동일 바이트 해시)
 *  - 손상(0바이트) 의심
 *
 * 부족/초과는 게시판 규칙에 따라 정상/주의/오류로 판정한다.
 * 순수 로직 → 단위 테스트 가능.
 */
object ImageValidator {

    enum class Severity { NORMAL, CAUTION, ERROR }

    data class Result(
        val severity: Severity,
        val count: Int,
        val thumbnailCount: Int,
        val bodyCount: Int,
        val issues: List<String>
    )

    /**
     * @param images 파싱된 이미지
     * @param board 적용 게시판 프로필(이미지 범위 기준)
     * @param declaredCount metadata.image_count (nullable)
     */
    fun validate(images: List<ParsedImage>, board: BoardProfile, declaredCount: Int?): Result {
        val issues = ArrayList<String>()
        var severity = Severity.NORMAL

        fun raise(to: Severity) {
            if (to.ordinal > severity.ordinal) severity = to
        }

        val count = images.size
        val thumbs = images.count { it.role == ImageRole.THUMBNAIL }
        val bodies = images.count { it.role == ImageRole.BODY }

        // (1) 장수 범위
        when {
            count < board.imageMin -> {
                raise(Severity.ERROR)
                issues.add("이미지가 최소 ${board.imageMin}장보다 부족합니다(현재 ${count}장).")
            }
            count > board.imageMax -> {
                raise(Severity.ERROR)
                issues.add("이미지가 최대 ${board.imageMax}장을 초과했습니다(현재 ${count}장).")
            }
            count < board.imageRecommended -> {
                raise(Severity.CAUTION)
                issues.add("권장 ${board.imageRecommended}장보다 ${board.imageRecommended - count}장 부족합니다(현재 ${count}장).")
            }
        }

        // (2) metadata.image_count 불일치
        if (declaredCount != null && declaredCount != count) {
            raise(Severity.CAUTION)
            issues.add("metadata의 image_count(${declaredCount})와 실제 장수(${count})가 다릅니다.")
        }

        // (3) 대표 이미지
        when {
            thumbs == 0 -> {
                raise(Severity.CAUTION)
                issues.add("대표 이미지(썸네일)가 없습니다.")
            }
            thumbs > 1 -> {
                raise(Severity.CAUTION)
                issues.add("대표 이미지가 ${thumbs}개입니다(1개 권장).")
            }
        }

        // (4) 본문 이미지 순서 중복
        val bodyIndices = images.filter { it.role == ImageRole.BODY }.map { it.orderIndex }
        val dupIndices = bodyIndices.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (dupIndices.isNotEmpty()) {
            raise(Severity.CAUTION)
            issues.add("본문 이미지 순서가 중복됩니다: ${dupIndices.sorted().joinToString(", ")}")
        }

        // (5) 내용 중복(동일 바이트)
        val hashes = HashMap<String, Int>()
        var dupContent = 0
        for (img in images) {
            if (img.byteSize == 0) continue
            val h = ContentFingerprint.sha256(img.bytes)
            hashes[h] = (hashes[h] ?: 0) + 1
        }
        hashes.values.forEach { if (it > 1) dupContent += (it - 1) }
        if (dupContent > 0) {
            raise(Severity.CAUTION)
            issues.add("내용이 동일한 이미지가 ${dupContent}장 있습니다.")
        }

        // (6) 손상(0바이트) 의심
        val broken = images.count { it.byteSize == 0 }
        if (broken > 0) {
            raise(Severity.ERROR)
            issues.add("비어있거나 손상된 이미지가 ${broken}장 있습니다.")
        }

        if (issues.isEmpty()) issues.add("이미지 ${count}장 · 정상")

        return Result(severity, count, thumbs, bodies, issues)
    }
}
