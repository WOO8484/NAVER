# 완전 자동화 흐름 (AutomationOrchestrator) — v1.1.0

## 1. 책임 분리
- **NaverAccessibilityService**: 화면 탐색 + 1개 동작만 수행하고 `ActionResult` 를 반환한다.
  전체 순서를 스스로 진행하지 않는다.
- **AutomationStepExecutor / AutomationStepExecutorImpl**: 오케스트레이터와 접근성 서비스 사이의 어댑터.
  접근성 서비스가 꺼져 있으면 항상 `NeedsUser` 를 안전하게 반환한다.
- **AutomationOrchestrator**: 단계 순서, 재시도, 타임아웃, 일시정지/이어하기/즉시중지, 진행 상태 갱신을
  전담하는 유일한 주체.

## 2. 한 틱(tick)의 동작
```
현재 상태 확인 (halting 이면 정지)
→ runStep(state): 상태에 맞는 executor 호출 1~수개(사진 앨범 진입 등 예외적으로 안전한 범위 내 순차 호출)
→ applyResult(state, result):
   Success   → (사진/태그처럼 반복 필요하면 같은 상태 유지) 아니면 다음 상태로 전이 후 다음 틱 예약
   Retryable → 타임아웃 초과 시 즉시 PAUSED, 아니면 재시도 횟수 확인 후 재시도 또는 PAUSED
   NeedsUser → 즉시 PAUSED(사용자의 화면 조작 1개를 기다림)
   Blocked   → 즉시 PAUSED(재시도하지 않음, 안전 정책에 의한 의도적 차단)
```

## 3. 테스트 용이성
`scheduleNext`(다음 틱 예약)와 `clock`(현재 시각)을 생성자에서 주입받는다. 프로덕션에서는
`StepTimeoutController`(Handler 기반 단일 토큰 스케줄러)를 사용하고, 단위 테스트에서는 동기 람다와
증가하는 가짜 시계를 주입해 실제 Handler/Looper 없이 순서·재시도·타임아웃을 검증한다
(`AutomationOrchestratorTest`, `FakeAutomationStepExecutor`).

## 4. 게시판 자동 매칭 실패 시 처리
`SELECTING_CATEGORY` 에서 `BoardMatcher.matchAgainstDisplayedTexts()` 가 실패하면 오케스트레이터는
`categoryAwaitingManualPick` 플래그를 세우고 `NeedsUser` 로 일시정지한다. 사용자가 네이버 화면에서
직접 게시판을 선택한 뒤 [이어하기]를 누르면, 다음 틱에서 화면에 표시된 게시판명을 그대로 읽어
`SessionRepository.manualCategorySelection` 에 기록하고 다음 단계로 진행한다(임의 게시판 자동 선택 없음).

## 5. 절대 금지 사항 준수 확인
- `READY_FOR_USER` 이후 상태에서는 `runStep()` 이 호출되지 않는다(`state.isHalting` 이 tick 진입을 막음).
- 발행/등록/게시/공개/임시저장/예약 계열 클릭 코드는 이 파일 어디에도 없다(코드 검색으로 정적 확인 가능).

## 6. 검증 상태
- 순서/재시도/타임아웃/일시정지·이어하기/즉시중지/게시판 수동선택 기록: `PASS-실행검증`(JVM 단위 테스트, 가짜 executor).
- 실제 네이버 앱 화면을 대상으로 한 end-to-end 자동 실행: `미검증-실기기필요`.
