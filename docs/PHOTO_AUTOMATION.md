# 사진 자동 선택 — v1.1.0

## 1. 저장 단계
`ImageConverter` 가 PNG/JPG/JPEG 는 원본 유지, WEBP 등 불안정 형식은 알파 유무에 따라 PNG/JPEG(품질 95)로
변환한다. `ImageSaveHelper.saveAll()` 이 세션별 고유 파일명(`GPTG_<postId>_<sessionId>_NN_role.ext`)으로
`Pictures/GPT공작소` 앨범에 저장하고 `SavedImageEntry` manifest(세션ID/원본명/저장명/URI/역할/순서/저장시각/SHA-256)를
반환한다.

## 2. 선택 계획
`PhotoSelectionPlanner.buildPlan()` 이 manifest로부터 "대표 → 본문 순서" 계획을 만든다.
`verifySelection()`/`verifyFinalCount()` 가 중복 선택과 개수 불일치를 감지해 자동 진행을 막는다.
**"갤러리의 최신 N장" 같은 검증 없는 방식은 코드 어디에도 없다.**

## 3. 실행 순서 (AutomationOrchestrator)
```
OPENING_PHOTO_PICKER  사진 첨부 버튼 클릭
OPENING_GPT_ALBUM     선택기 패키지 식별 → 앨범 메뉴 열기 → GPT공작소 앨범 선택
SELECTING_PHOTOS       사진을 1장씩 선택(선택 성공마다 개수 증가, 예상 개수 도달까지 반복)
VERIFYING_PHOTO_COUNT  선택기에 표시된 "선택됨 N" 을 읽어 예상 개수와 정확히 일치하는지 확인
CONFIRMING_PHOTOS       PHOTO_PICKER 화면 문맥에서만 허용되는 안전 완료 버튼 클릭
VERIFYING_PHOTO_ATTACH  글쓰기 화면 복귀 확인
```
1장/5장/10장 모두 같은 로직으로 동작하도록 설계했다(개수에 의존한 특수 분기 없음).

## 4. ⚠️ 기본 상태에서는 항상 수동 대기
`selector_rules.json` 의 `PHOTO_PICKER.package_names` 가 비어 있으면(기본값) `identifyPhotoPicker()` 가
항상 `NeedsUser` 를 반환해 자동 진행을 멈추고 "GPT공작소 앨범에서 표시된 사진을 직접 선택해 주세요" 로
안내한다. 실기에서 확인한 선택기 패키지를 `selector_rules.json` 과
`app/src/main/res/xml/accessibility_service_config.xml` 양쪽에 반영해야 자동 선택이 실제로 동작한다.

## 5. 검증 상태
- 저장 파일명 규칙, 계획(1/5/10장, 순서, 중복/개수 검증) 로직: `PASS-실행검증`(`PhotoSelectionPlannerTest`, `SavedImageManifestTest`).
- 이미지 변환 형식 판정(WEBP→PNG/JPEG): `PASS-실행검증`(`ImageConverterFormatTest`, 순수 판정 로직만).
- 실제 Bitmap 디코딩/변환, 실제 삼성 사진 선택기에서의 클릭/카운트 읽기: `미검증-실기기필요`.
