# GPT 공작소 Android v1.1.0

네이버 블로그 글쓰기 보조 자동화를 위한 Android 프로젝트입니다. Android Studio에서 바로 열 수 있는 전체 소스, Gradle Wrapper, 단위 테스트, GitHub Actions 빌드 설정을 포함합니다.

## 저장소 구성

- `app/` — Android 앱 소스와 단위 테스트
- `gradle/`, `gradlew`, `gradlew.bat` — Gradle Wrapper
- `.github/workflows/android-build.yml` — `clean test assembleDebug` 실행 및 APK·로그·테스트 결과 업로드
- `.github/workflows/release.yml` — GitHub Secrets를 사용한 서명 Release APK 빌드
- `docs/` — 구조, 자동화 흐름, 빌드, 보안, 서명 안내
- `samples/` — 입력 패키지 예제

## 개발 환경

- JDK 17
- Android Gradle Plugin 8.5.2
- Gradle 8.9
- Kotlin 1.9.24
- compileSdk / targetSdk 34
- minSdk 30

## Android Studio에서 실행

1. 이 저장소 폴더를 Android Studio에서 엽니다.
2. JDK를 17로 설정합니다.
3. Gradle 동기화를 실행합니다.
4. Android 11(API 30) 이상의 기기 또는 에뮬레이터를 선택합니다.
5. `app` 실행 구성을 실행합니다.

터미널 빌드:

```bash
./gradlew clean test assembleDebug
```

Windows:

```bat
gradlew.bat clean test assembleDebug
```

Debug APK 경로:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions 빌드

저장소를 GitHub에 올리면 `main` 브랜치 push와 pull request에서 Android CI가 실행됩니다. 수동 실행은 GitHub의 **Actions → Android CI Build → Run workflow**에서 할 수 있습니다.

Actions 아티팩트:

- `app-debug-apk`
- `build-log`
- `unit-test-results-xml`
- `unit-test-results-html`
- `lint-results`

## Release 서명

서명 키 파일과 비밀번호는 저장소에 올리지 않습니다. 다음 GitHub Secrets를 설정한 뒤 **Android Release Build (Signed)** 워크플로를 수동 실행합니다.

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

상세 절차는 `docs/SIGNING_SETUP_GUIDE.md`를 확인하세요.

## 보안 주의

다음 파일은 절대 GitHub에 올리지 마세요.

- `local.properties`
- `*.jks`, `*.keystore`
- API 키, 비밀번호, 토큰
- `.env`, `secrets.properties`, `keystore.properties`

## 현재 검증 상태

프로젝트에는 빌드·테스트 워크플로가 포함되어 있습니다. 실제 GitHub Actions의 최종 성공 여부와 생성 APK의 실기 동작은 업로드 후 Actions 결과 및 실제 Android 기기에서 확인해야 합니다. 기존 검토에서 자동화 성공 결과 처리와 재시작 시 태그 진행 위치 복원 부분은 추가 실기 검증 대상으로 분류되었습니다.
