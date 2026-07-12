# BUILD_STATUS — GPT 공작소 v1.1.0

## 완료 (코드/문서 작성 완료, 정적 검토 완료)
- 완전 자동화 오케스트레이터 및 관련 모듈(automation/*) 구현
- 상태머신 확장(자동화 20단계, 안전 전이, 재시도/타임아웃, 직렬화)
- 선택자 규칙 구조화 재구현 + 접근성 서비스 실연결
- 사진 자동 선택 파이프라인(변환/매니페스트/계획) 구현
- 게시판 자동 선택 확장(실제 화면 텍스트 매칭 + 수동선택 감지)
- 세션 영구 저장 + 이어하기 UI + 단일 세션 잠금
- UI 재구성(전체 자동 실행 카드, 접이식 수동 복구, 선택자 관리 카드)
- 단위 테스트 50개 신규 작성(기존 46개 유지) — 코드 검토로 로직 정합성 확인
- CI 워크플로우(android-build.yml, release.yml) — 이미 v1.1.0 요구사항(Java17/캐시/테스트결과업로드/
  빌드로그업로드/lint/디버그APK조건부업로드/실패시워크플로우실패, 시크릿기반서명/시크릿없으면중단)
  충족 상태로 구성되어 있음을 확인
- 문서 20종(00_README_FIRST, README, CHANGELOG, TEST_RESULT, SECURITY_REVIEW, ARCHITECTURE,
  AUTOMATION_FLOW, STATE_MACHINE, SELECTOR_RULES, PHOTO_AUTOMATION, BOARD_AUTOMATION,
  RECOVERY_AND_RESUME, BUILD_LOCAL_AND_CI, GITHUB_ACTIONS_BUILD_GUIDE, SIGNING_SETUP_GUIDE,
  INSTALLATION_TROUBLESHOOTING, DATA_SCHEMA, BUILD_STATUS, SHA256_MANIFEST.json) 작성 완료
- 1장/10장 전용 시험 샘플 ZIP 신규 생성

## 미실행 (환경 제한 — 이 작업 샌드박스는 네트워크 차단)
- `./gradlew clean test assembleDebug` 실제 실행 (Gradle 배포판 다운로드 자체가 HTTP 403으로 실패)
- 단위 테스트 96개의 실제 JVM 실행 결과
- `./gradlew lint` 실행
- APK(debug/release) 생성 — 이 결과물에 APK 파일이 없다(가짜/이전버전 APK로 대체하지 않음)

## 미검증 (실기기 필요)
- Samsung SM-G996N/Android 15에서의 전체 자동 실행 동작 전부
- `selector_rules.json` 내장 기본값의 실제 UI 일치 여부
- 사진 선택기 패키지명, 앨범/그리드/완료 버튼 선택자
- 게시판 목록 다이얼로그 실제 구조
- 화면 전환/화면 잠금/전화 수신 중 자동 일시정지의 실제 반응 속도
- Google Play Protect 통과 여부, 서명된 release APK 설치/업데이트

## 다음 단계 권장 순서
1. GitHub에 푸시 → `android-build.yml` 로 실제 `clean test assembleDebug` 실행, 실패하는 테스트가
   있으면 우선 수정.
2. debug APK를 Samsung SM-G996N에 설치 → 접근성 로그를 보며 `selector_rules.json` 값을 실기에 맞게
   교정([시험 적용] 반복).
3. 사진 선택기 패키지 확인 후 `selector_rules.json`과 `accessibility_service_config.xml`에 반영.
4. 위 과정이 안정화되면 [정식 적용] + `SIGNING_SETUP_GUIDE.md` 대로 서명 Secrets 등록 후
   `release.yml` 로 서명 APK 배포.
