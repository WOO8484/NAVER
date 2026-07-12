# ARCHITECTURE — 모듈 구조 (v1.1.0)

## 설계 원칙
1. **순수 로직 / Android 로직 분리**: 알고리즘(파싱 규칙·정규화·해시·중복판정·게시판매칭·이미지검증·상태머신·
   오케스트레이터 순서 결정)은 Android 의존성이 없는 순수 Kotlin으로 두어 단위 테스트가 가능하게 함.
2. **검증 로직 이식**: v0.1.1/v1.0.x 에서 검증된 보안/접근성/저장 로직은 규칙 변경 없이 이식하고,
   자동화 확장은 기존 개별 입력 함수를 재사용하는 방식으로 추가(중복 구현 금지, 지시서 2-12).
3. **접근성 서비스는 단일 동작만**: `NaverAccessibilityService` 는 "화면 탐색 + 1개 동작" 결과
   (`ActionResult`)만 반환하고, 전체 순서는 `AutomationOrchestrator` 가 전담한다(지시서 5).
4. **빌드 의존성 최소화**: 이력 DB는 SQLiteOpenHelper 사용, Robolectric/Mockito 미도입 → 오케스트레이터를
   포함한 자동화 로직도 순수 Kotlin 인터페이스(`AutomationStepExecutor`)로 감싸 JVM 단위 테스트 가능하게 함.

## 패키지 구성
```
com.gptgongjakso.naverwriterhelper
├─ GptGongjaksoApp            앱 초기화(Application)
├─ MainActivity              카드형 UI + 자료 파이프라인 + 전체 자동 실행 진입점
├─ model/                    순수 데이터 모델
│  ├─ ImageModels            ImageRole, ParsedImage
│  ├─ NaverPostData          자료 1세트(+metadata/해시)
│  ├─ PostMetadata           metadata 스키마 2.1
│  ├─ BoardProfile           게시판 프로필
│  ├─ PipelineState          자료처리 7단계 + 자동화 20단계 + 종료류 5종
│  └─ DuplicateModels        판정/이력 레코드
├─ parser/                   [순수] TagNormalizer, MetadataMapper / [Android] PackageParser, MetadataParser
├─ dedup/  [순수]            ContentFingerprint(SHA256/지문), DuplicateChecker(판정)
├─ board/  [순수]            BoardMatcher(정확/별칭/실제화면텍스트 매칭), BoardProfileRepository(9개 프로필)
├─ image/  [순수 위주]       ImageValidator(가변 장수 검사), ImageConverter(형식 변환 판정),
│                            SavedImageManifest(저장 파일명/직렬화), PhotoSelectionPlanner(선택 계획/검증)
├─ statemachine/ [순수]      PipelineStateMachine (advanceTo/retryOrPause/직렬화)
├─ selector/ [Android I/O]   SelectorRules (구조화 규칙, 스키마 검증, 정식/이전/기본 3단계 관리)
├─ automation/ [순수 핵심]   ActionResult, AutomationStepExecutor(인터페이스), AutomationOrchestrator(순수 순서 로직),
│                            AutomationTimeouts, StepTimeoutController(Handler 어댑터),
│                            AutomationSessionStore(영구 저장 + 단일세션 잠금)
│  └─ AutomationStepExecutorImpl [Android]  접근성 서비스/NaverLaunchHelper 연결 어댑터
├─ helper/ [Android]         ImageSaveHelper(세션별 파일명), ClipboardInputHelper, NaverLaunchHelper, PermissionGuideHelper
├─ service/ [Android]        NaverAccessibilityService(선택자 기반 탐색+ActionResult), FloatingControlService(상태 구독형 UI),
│                            TagInputController, SafetyMonitor(화면꺼짐/권한해제 감지)
├─ store/ [Android]          SessionRepository(세션ID/오케스트레이터/매니페스트 보유), AutomationLogStore
│  └─ db/                    HistoryStore(SQLiteOpenHelper 이력 DB)
├─ instruction/ [Android]    InstructionManager (기초, 이번 버전 확장 범위 아님)
└─ diagnostics/ [Android]    DiagnosticsExporter (기초)
```

## 처리 파이프라인 (상태머신) — 전체 흐름은 `STATE_MACHINE.md`, `AUTOMATION_FLOW.md` 참고
```
[자료 처리]  RECEIVED → VALIDATING → DUPLICATE_CHECKING → PARSING → STORING
            → CONVERTING_IMAGES → SAVING_IMAGES → READY_TO_AUTOMATE
[자동 실행]  OPENING_NAVER → ... → INPUTTING_TAGS → VERIFYING_TAGS → READY_FOR_USER
비정상: PAUSED(재개지점 보관) / FAILED / CANCELLED / COMPLETED_BY_USER
```
- 자동 진행은 **절대 발행/임시저장으로 이어지지 않고 READY_FOR_USER 에서 멈춥니다.**
- 화면 꺼짐/권한 해제 → SafetyMonitor 가 오케스트레이터를 일시정지. 앱 전환/전화(다른 앱 전면) →
  각 자동화 단계 자체의 화면 확인으로 자연히 정지.

## 데이터 흐름 (v1.1.0)
1. ZIP 수신(파일 선택 / 공유 / 열기) → `PackageParser` 파싱 + ZIP/본문 SHA-256.
2. `metadata.json` → `MetadataParser` → `MetadataMapper` → `PostMetadata`.
3. `BoardMatcher` 로 게시판 자동 매칭(1차, metadata 기준).
4. `HistoryStore.all()` + `DuplicateChecker` → 판정(동일글이면 진행 차단).
5. `ImageValidator` 로 가변 이미지 검사 → `ImageConverter`/`ImageSaveHelper` 로 세션별 파일명 저장
   → `SavedImageEntry` manifest 생성 → `READY_TO_AUTOMATE`.
6. 사용자가 [전체 자동 실행 시작]을 누르면 `AutomationOrchestrator` 가 네이버 앱 실행부터 태그 입력까지
   순차 자동 진행하고, 각 단계에서 `BoardMatcher`(2차, 실제 화면 텍스트 기준)/`PhotoSelectionPlanner`/
   `SelectorRules` 를 사용해 검증한다.
7. `READY_FOR_USER` 도달 시 정지. 사용자가 발행/임시저장을 직접 처리하고 ⑥ 카드에서 결과를 기록한다.

## 스레딩
- 파싱/DB/이미지 저장 I/O 는 `Dispatchers.IO`. UI/상태 갱신은 메인 스레드.
- 오케스트레이터의 다음 틱 예약은 `StepTimeoutController`(메인 Looper 기반 Handler)를 통해 이뤄진다.
- 전역 상태(SessionRepository/AutomationLogStore)는 리스너 구독으로 UI 에 반영.
