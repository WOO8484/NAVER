# GitHub Actions 빌드 가이드 — v1.1.0

## 1. 저장소에 올리기
이 소스 전체(`gradlew`, `gradle/wrapper/*`, `settings.gradle`, `build.gradle`, `app/` 등)를 GitHub
저장소에 그대로 커밋합니다. `.github/workflows/android-build.yml` 이 있으면 자동으로 인식됩니다.

## 2. 디버그 빌드 (android-build.yml)
- 트리거: `workflow_dispatch`(수동), `main` 브랜치 push, `main` 대상 PR.
- 동작: JDK 17 설정 → Gradle 캐시 → `chmod +x gradlew` → `./gradlew clean test assembleDebug --stacktrace`
  (성공/실패와 관계없이 `build.log` 로 저장) → 단위 테스트 결과(XML/HTML) 업로드 → `./gradlew lint` 실행 후
  결과 업로드 → **빌드가 성공했을 때만** `app-debug.apk` 아티팩트 업로드 → 빌드가 실패했으면 워크플로우
  자체를 실패 상태로 남긴다(로그만 남기고 성공으로 위장하지 않음).
- Actions 탭 → 해당 실행 → Artifacts 에서 `build-log`, `unit-test-results-xml`,
  `unit-test-results-html`, `lint-results`, (성공 시) `app-debug-apk` 를 내려받습니다.

## 3. 서명된 릴리즈 빌드 (release.yml)
- 트리거: `workflow_dispatch`(수동 실행만 — 실수로 커밋마다 릴리즈되지 않도록).
- `ANDROID_KEYSTORE_BASE64`/`ANDROID_KEYSTORE_PASSWORD`/`ANDROID_KEY_ALIAS`/`ANDROID_KEY_PASSWORD`
  Secrets가 모두 있어야 진행됩니다. 하나라도 없으면 **release APK를 만든 것처럼 보고하지 않고**
  즉시 실패 상태로 중단합니다.
- 키스토어는 실행 중에만 디코딩되어 사용되고, 스텝이 끝나면 즉시 삭제됩니다(로그/아티팩트에 남지 않음).
- 성공 시 `app-release-apk` 아티팩트로 서명된 `app-release.apk` 를 받습니다.

## 4. Secrets 등록 방법 (요약)
1. 저장소 Settings → Secrets and variables → Actions → New repository secret.
2. `ANDROID_KEYSTORE_BASE64` : `base64 -w0 release.jks` 결과를 붙여넣기.
3. `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` : 각각 실제 값.
자세한 키스토어 생성 절차는 `SIGNING_SETUP_GUIDE.md` 참고.

## 5. 이 환경에서 실행하지 못한 이유
현재 작업 샌드박스는 **네트워크 접근이 차단**되어 있어 `gradlew` 가 Gradle 배포판
(`gradle-8.9-bin.zip`)이나 Android Gradle Plugin/Kotlin 플러그인/의존성을 내려받을 수 없습니다.
그래서 이 대화 안에서는 `./gradlew clean test assembleDebug` 를 실행할 수 없었습니다(`미실행-환경제한`).
실제 실행은 GitHub Actions(네트워크 있음) 또는 네트워크가 열린 로컬 PC/Android Studio에서 이뤄져야 합니다.
