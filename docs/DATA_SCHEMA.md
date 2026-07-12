# DATA_SCHEMA — 자료 ZIP & metadata 2.1

## 자료 ZIP 구조 (v0.1.1 호환)
아래 두 구조를 모두 지원합니다(대소문자 무시, 하위 폴더의 파일명은 basename 만 사용).

**구조 A (텍스트 우선)**
```
title.txt          제목(첫 줄)
content.txt        본문(.md/.html 도 허용)
tags.txt           태그(naver_tags.txt 도 허용)
thumbnail.png      대표 이미지(thumb* 포함 인식)
body-01.png ...    본문 이미지(body* + 숫자)
```

**구조 B (metadata 우선)**
```
metadata.json      아래 스키마 2.1
content.(txt|md|html)
naver_tags.txt
thumbnail.png / body-01.png ...
```

### 우선순위 (v0.1.1 규칙 유지)
- 제목: `title.txt` > `metadata.title` > 본문 첫 줄 > "(제목 없음)"
- 본문: `content.txt` > `content.md` > `content.html`(태그 제거)
- 태그: `tags.txt` > `naver_tags.txt` > `metadata.tags`
- 이미지 확장자: png/jpg/jpeg/webp

### 자원 제한(보안, v0.1.1 이식)
원본 ZIP ≤ 50MB · 전체 해제 ≤ 100MB · 단일 엔트리 ≤ 25MB · 텍스트 ≤ 2MB · 엔트리 ≤ 50개 · 이미지 ≤ 10장 · 압축률 ≤ 100. 경로탈출/제어문자/중복 basename 은 거부.

## metadata.json — 스키마 2.1
```jsonc
{
  "schema_version": "2.1",
  "post_id": "20260711-gov-001",     // 고유 식별(중복/수정본 판별)
  "title": "정부지원 안내",
  "naver_category": "정부지원",       // 게시판 자동 매칭 대상(표시명/별칭)
  "tags": ["에너지바우처", "신청조건"], // 배열 또는 "공백/쉼표 구분 문자열"
  "image_count": 5,                   // 실제 장수(불일치 시 주의)
  "image_min": 1,                     // 게시판 기준 override(선택)
  "image_recommended": 5,
  "image_max": 10,
  "content_version": 1,               // 수정본 버전(↑ 이면 수정본으로 인정)
  "topic_key": "에너지바우처",          // 주제(주의 판정)
  "topic_angle": "신청조건",           // 관점
  "instruction_version": "1.0.0"      // 지시문 버전(대조용)
}
```

### 구버전 변환 규칙 (MetadataMapper)
- `schema_version` 누락 + 2.1 전용 키(naver_category/image_min/image_max/topic_key/topic_angle) 없음 → **"1.0"** 으로 간주(승격 처리).
- 별칭 인식: `id`→post_id, `category`/`board`→naver_category, `keyword`→topic_key, `angle`→topic_angle, `recommended_image_count`→image_recommended.
- `tags` 가 문자열이면 공백/쉼표로 분리.
- `content_version` 누락 시 1.

## 이력 DB (HistoryStore) 저장 항목 (지시서 18)
`post_id, title, body_text, board_key, topic_key, topic_angle, tags, created_at, publish_status, zip_sha256, body_sha256, body_fingerprint, content_version, last_verdict`

- 인덱스: zip_sha256 / body_sha256 / body_fingerprint / created_at.
- `publish_status`: 미확인 → 사용자 기록(발행완료/임시저장/취소).
- **로그(AutomationLogStore)에는 태그/본문 내용을 남기지 않습니다.** 이력 DB 는 앱 내부에만 저장되며 백업에서 제외됩니다.

## 중복 판정 (DuplicateChecker)
| 판정 | 조건 | 자동 진행 |
|---|---|---|
| 동일 글(IDENTICAL) | zip_sha256 또는 body_sha256 완전 일치 | **차단** |
| 중복 의심(SUSPECT) | body_fingerprint 일치, body_sha256 다름(사소한 편집) | 경고 후 사용자 판단 |
| 주의(CAUTION) | 기간(게시판 duplicate_window_days) 내 동일 주제/제목 | 경고 후 사용자 판단 |
| 정상(NORMAL) | 위 해당 없음 | 진행 |
| 수정본 예외 | 동일 post_id + content_version↑ + 본문 다름 → 동일 글 아님 | 진행 |
