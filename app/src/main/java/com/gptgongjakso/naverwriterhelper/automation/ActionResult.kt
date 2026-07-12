package com.gptgongjakso.naverwriterhelper.automation

/**
 * 접근성 서비스의 "화면 탐색 + 1개 동작 실행" 결과 (작업지시서 12, NaverAccessibilityService).
 *
 * 접근성 서비스는 전체 순서를 관리하지 않고, 이 결과 객체만 오케스트레이터에 돌려준다.
 * `performAction()` 호출이 true 를 반환했다는 사실만으로 성공 처리하지 않고,
 * 값 변화·화면 전환 등 후속 결과를 확인한 뒤에만 [Success] 를 반환해야 한다(작업지시서 5.2).
 */
sealed class ActionResult {
    /** 동작이 성공했고, 성공을 뒷받침하는 증거(로그용 짧은 문자열 — 원문/계정정보 없음). */
    data class Success(val evidence: String) : ActionResult()

    /** 일시적 실패로 같은 단계를 재시도할 수 있는 상태. */
    data class Retryable(val reason: String) : ActionResult()

    /** 자동으로 해결할 수 없어 사용자의 1개 동작이 필요한 상태(임의 진행 금지). */
    data class NeedsUser(val reason: String) : ActionResult()

    /** 안전 정책에 의해 의도적으로 차단된 동작(위험 버튼 등). 재시도하지 않는다. */
    data class Blocked(val reason: String) : ActionResult()
}
