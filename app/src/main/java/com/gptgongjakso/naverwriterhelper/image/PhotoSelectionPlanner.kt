package com.gptgongjakso.naverwriterhelper.image

/**
 * 사진 자동 선택 계획/검증 (작업지시서 8.3~8.4). v1.1.0 신규.
 *
 * "갤러리의 최신 N장"처럼 검증 없이 선택하는 방식을 금지한다(지시서 8.3, 20장).
 * 현재 세션 저장 개수와 선택 예정/완료 개수가 정확히 일치하는지, 같은 항목을
 * 중복 선택하지 않는지를 순수 로직으로 검증한다. 실제 클릭은 접근성 서비스가
 * 이 계획을 따라 수행하고, 결과를 이 검증 함수에 넘겨 확인받는다.
 */
object PhotoSelectionPlanner {

    data class PlanItem(val orderIndex: Int, val savedName: String, val isThumbnail: Boolean)

    data class Plan(val items: List<PlanItem>, val expectedCount: Int)

    /** manifest 항목으로부터 "대표 → 본문 순서" 선택 계획을 만든다(지시서 8.2/8.4). */
    fun buildPlan(entries: List<SavedImageEntry>): Plan {
        val sorted = entries.sortedWith(
            compareByDescending<SavedImageEntry> { it.role.name == "THUMBNAIL" }
                .thenBy { it.orderIndex }
        )
        val items = sorted.map { PlanItem(it.orderIndex, it.savedName, it.role.name == "THUMBNAIL") }
        return Plan(items, entries.size)
    }

    sealed class VerifyResult {
        object Ok : VerifyResult()
        data class CountMismatch(val expected: Int, val actual: Int) : VerifyResult()
        data class DuplicateSelection(val savedName: String) : VerifyResult()
    }

    /** 선택 진행 중: 같은 항목을 두 번 선택하지 않았는지, 예정 개수를 넘지 않았는지 확인. */
    fun verifySelection(plan: Plan, selectedSoFar: List<String>): VerifyResult {
        val dup = selectedSoFar.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        if (dup != null) return VerifyResult.DuplicateSelection(dup.key)
        if (selectedSoFar.size > plan.expectedCount) {
            return VerifyResult.CountMismatch(plan.expectedCount, selectedSoFar.size)
        }
        return VerifyResult.Ok
    }

    /** 완료 시점: 최종 선택 개수가 기대 개수와 정확히 일치하는지 확인(불일치 시 중단, 지시서 20). */
    fun verifyFinalCount(plan: Plan, finalSelectedCount: Int): VerifyResult =
        if (finalSelectedCount == plan.expectedCount) VerifyResult.Ok
        else VerifyResult.CountMismatch(plan.expectedCount, finalSelectedCount)
}
