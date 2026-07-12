# 선택자 규칙 (selector_rules.json) — v1.1.0

## 1. 관리 3단계
1. 정식 적용본: `files/selector_rules_active.json`
2. 이전 정상본: `files/selector_rules_previous.json` (정식 적용 시 자동 백업)
3. 앱 내장 기본본: `assets/selector_rules.json`

로드 우선순위는 위 순서다. 파싱 실패/스키마 불일치 시 다음 우선순위로 자동 폴백한다.
인터넷 자동 다운로드는 하지 않는다(SAF로 사용자가 직접 파일을 선택해 불러온다).

## 2. 스키마
`schema_version`(현재 지원: `"1.1"`), `rules_version`, `target_app.package`, `screens{NAVER_HOME,
WRITE_EDITOR, CATEGORY_DIALOG, PHOTO_PICKER}` 로 구성된다. 각 화면은 역할별 선택자 후보 배열
(`signals`, `title_field`, `category_items`, `safe_confirm_button` 등)을 가진다.

선택자 후보 필드: `view_id_contains`, `text_exact`, `text_contains`, `desc_exact`, `desc_contains`,
`hint_contains`, `class_name`, `editable_only`, `clickable_only`, `screen_role`(안전 클릭 문맥 확인용).

## 3. 탐색 우선순위
후보 배열의 앞쪽 항목부터 순서대로 시도하고, 첫 매치를 사용한다(대체 선택자 체인).
`NaverAccessibilityService.findFirst()/findAll()` 이 이를 구현한다.

## 4. ⚠️ 내장 기본본은 미검증 상태
`assets/selector_rules.json` 의 `rules_version` 은 `"1.1.0-builtin-unverified"` 로 표시되어 있다.
값은 네이버 블로그 앱의 실제 리소스 ID/텍스트를 확인하지 못한 **최선 추정치**(한글 힌트 텍스트
"제목"/"본문"/"태그"/"카테고리"/"사진"/"앨범" 등)이며, 실기에서 재검증 후 SAF로 새 규칙을
불러와 [시험 적용] → 확인 → [정식 적용] 순서로 교체해야 한다.

**`PHOTO_PICKER.package_names` 는 기본값이 빈 배열이다.** 실기에서 확인한 삼성 갤러리/시스템
사진 선택기 패키지명을 채우기 전까지, 사진 자동 선택은 항상 `NeedsUser` 로 안전하게 멈추고
사용자의 수동 선택을 기다린다(작업지시서 8.5: 미확인 상태에서 모든 앱 허용 금지).
`accessibility_service_config.xml` 의 `android:packageNames` 도 함께 갱신해야 실제로 그 화면을
관찰할 수 있다(패키지 가시성 제한).

## 5. 검증 상태
- JSON 파싱/스키마 검증/폴백 체인 로직: `PASS-실행검증`(`SelectorRulesTest`).
- 선택자 값 자체가 실제 네이버 블로그 UI와 일치하는지: `미검증-실기기필요`.
