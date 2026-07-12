# 상태머신 (PipelineState / PipelineStateMachine) — v1.1.0

## 1. 상태 목록
`model/PipelineState.kt` 참고. 자료 처리 단계(RECEIVED~SAVING_IMAGES)와 자동화 단계
(READY_TO_AUTOMATE~VERIFYING_TAGS), 종료류(READY_FOR_USER/PAUSED/FAILED/CANCELLED/COMPLETED_BY_USER)로 나뉜다.

```
RECEIVED → VALIDATING → DUPLICATE_CHECKING → PARSING → STORING
→ CONVERTING_IMAGES → SAVING_IMAGES → READY_TO_AUTOMATE
→ OPENING_NAVER → WAITING_NAVER_HOME → OPENING_WRITE_SCREEN → VERIFYING_WRITE_SCREEN
→ OPENING_CATEGORY → SELECTING_CATEGORY → VERIFYING_CATEGORY
→ INPUTTING_TITLE → VERIFYING_TITLE → INPUTTING_BODY → VERIFYING_BODY
→ OPENING_PHOTO_PICKER → OPENING_GPT_ALBUM → SELECTING_PHOTOS → VERIFYING_PHOTO_COUNT
→ CONFIRMING_PHOTOS → VERIFYING_PHOTO_ATTACH
→ OPENING_TAG_FIELD → INPUTTING_TAGS → VERIFYING_TAGS
→ READY_FOR_USER
```
`READY_FOR_USER` 이후로는 어떤 클릭도 수행하지 않는다. 발행·임시저장은 사용자 전담이다.

## 2. 안전 전이(advanceTo)
`PipelineStateMachine.advanceTo(next)` 는 위 순서의 **인접 단계만** 허용한다. 순서를 건너뛰는
전이는 거부되고 `false` 를 반환한다(단위 테스트: `PipelineStateMachineTest`).
`transitionTo(next)` 는 검증 없는 관리용 API로, 기존 자료 처리 파이프라인(파싱~저장) 호환을 위해 남겨두었다.

## 3. 재시도/타임아웃
- `retryOrPause(reason)`: 상태별 재시도 횟수를 세고, `MAX_RETRIES_PER_STEP(2)` 초과 시 자동으로 `PAUSED`.
- 단계별 wall-clock 타임아웃은 `AutomationOrchestrator` 가 주입 가능한 `clock`/`timeoutMsFor` 로 판정한다
  (`AutomationTimeouts.forState()` 참고). 재시도 횟수와 타임아웃 중 먼저 도달하는 쪽이 우선한다.

## 4. 직렬화(이어하기)
`toPersistableMap()` / `restoreFrom()` 은 상태명·재개단계·일시정지사유·태그/사진 진행 카운트·
완료 단계 목록만 저장한다. **본문/제목 원문, 계정 정보는 절대 포함하지 않는다.**
영구 저장은 `automation/AutomationSessionStore` 가 담당한다 (`RECOVERY_AND_RESUME.md` 참고).

## 5. 검증 상태
- 상태 전이 로직: `PASS-실행검증`(JVM 단위 테스트로 실제 실행 확인, `PipelineStateMachineTest`).
- 실제 네이버 화면과의 정합성(각 상태가 실제로 그 화면에 대응하는지): `미검증-실기기필요`.
