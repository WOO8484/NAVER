# 게시판 자동 선택 — v1.1.0

## 1. 매칭 기준
1. `metadata.json.naver_category` 와 표시된 게시판 텍스트의 정확 일치
2. 게시판 프로필(`BoardProfileRepository`) 별칭과 정확 일치
3. 둘 다 없으면 **임의 선택하지 않고** 사용자 직접 선택으로 전환

`BoardMatcher.matchAgainstDisplayedTexts()` 가 공백 제거·소문자화 정규화 후 위 규칙으로 비교한다.
부분 문자열 일치는 어떤 경우에도 사용하지 않는다.

## 2. 실행 순서
```
OPENING_CATEGORY    카테고리 버튼 클릭
SELECTING_CATEGORY  목록 텍스트 수집 → 정확 일치 항목 클릭 (없으면 NeedsUser)
VERIFYING_CATEGORY  글쓰기 화면에 표시된 게시판명이 반영됐는지 확인
```

## 3. 미일치 시 사용자 직접 선택 흐름
자동 매칭에 실패하면 오케스트레이터가 일시정지하며 다음처럼 안내한다.
```
네이버에서 같은 게시판을 찾지 못했습니다.
게시판을 직접 선택하면 이어서 진행합니다.
```
사용자가 이어하기를 누르면, 오케스트레이터는 **직접 클릭하지 않고** 현재 화면에 표시된 게시판명을
읽어 `SessionRepository.manualCategorySelection` 에 기록한 뒤 다음 단계로 진행한다. 이 값을 게시판
프로필에 별칭으로 영구 저장하는 기능은 이번 버전 범위에 없다(작업지시서 7.2).

## 4. 안전한 확인 버튼 처리
게시판 목록의 "확인" 버튼은 `screen_role: "CATEGORY_DIALOG"` 가 명시된 선택자에서만, 그리고 클릭 직전
현재 화면이 실제로 `CATEGORY_DIALOG` 인지 재검사한 뒤에만 클릭한다(`safeClick()`). 발행/등록/게시/공개/
임시저장/예약 계열 텍스트는 이 안전 확인 버튼과 절대 혼동되지 않도록 전역 금지어 검사가 별도로 항상 적용된다.

## 5. 검증 상태
- 정확 일치/별칭 일치/미일치 null/부분일치 금지: `PASS-실행검증`(`BoardMatcherTest`).
- 사용자 수동 선택 감지·기록: `PASS-실행검증`(`AutomationOrchestratorTest`, 가짜 executor).
- 실제 네이버 게시판 목록 UI에서의 텍스트 수집/클릭: `미검증-실기기필요`.
