# 빌드 방법 (로컬 & GitHub Actions)

## 1. 로컬 빌드 (Android Studio)
1. Android Studio(Koala 이상 권장)로 프로젝트 루트 폴더를 엽니다.
2. JDK 17 이 설정되어 있는지 확인합니다(File > Settings > Build > Gradle > Gradle JDK = 17).
3. Gradle Sync 완료 후:
   - GUI: `Build > Build Bundle(s) / APK(s) > Build APK(s)`
   - 터미널: `./gradlew clean test assembleDebug`
4. 산출물(디버그): `app/build/outputs/apk/debug/app-debug.apk`
   - debug 빌드는 안드로이드 기본 `debug.keystore` 로 자동 서명되어 바로 설치 가능합니다.

### 로컬에서 서명된 release 빌드(선택)
환경변수를 설정한 뒤 `./gradlew assembleRelease` 를 실행합니다(값이 없으면 미서명 release 로 빌드됨).
```bash
export RELEASE_KEYSTORE_PATH=/absolute/path/to/release.jks
export RELEASE_KEYSTORE_PASSWORD=****
export RELEASE_KEY_ALIAS=****
export RELEASE_KEY_PASSWORD=****
./gradlew assembleRelease
```
> 키스토어와 비밀번호는 **소스/저장소에 절대 커밋하지 마세요.** 위 값은 환경변수로만 주입됩니다.

## 2. GitHub Actions 빌드 (CI)
`.github/workflows/android-build.yml` 가 다음 시점에 실행됩니다.
- `main`/`master` 브랜치 push, 해당 브랜치 대상 PR, 그리고 수동 실행(`workflow_dispatch`).

### 자동 수행
- `./gradlew clean test assembleDebug --stacktrace` 실행 (테스트 + 디버그 APK 빌드).
- 성공 시 아티팩트 업로드:
  - `gptgongjakso-debug-apk` : `app/build/outputs/apk/debug/*.apk`
  - `unit-test-results` : JUnit 리포트/결과 XML
  - `build-log` : 전체 빌드 로그(`build-log.txt`)

### 산출물 받기
GitHub 저장소 → Actions → 해당 실행 → 하단 Artifacts 에서 다운로드합니다.

## 3. 고정 release 서명용 GitHub Secrets 설정
서명된 release APK 를 CI 에서 만들려면 저장소에 아래 Secret 4개를 등록합니다.
(Settings → Secrets and variables → Actions → New repository secret)

| Secret 이름 | 설명 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | keystore(.jks) 파일을 base64 로 인코딩한 문자열 |
| `RELEASE_KEYSTORE_PASSWORD` | 키스토어 비밀번호 |
| `RELEASE_KEY_ALIAS` | 키 alias |
| `RELEASE_KEY_PASSWORD` | 키(alias) 비밀번호 |

### keystore 만들기 & base64 인코딩
```bash
# 1) 키스토어 생성(최초 1회) — 이 파일과 비밀번호는 안전하게 별도 보관
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias gptgongjakso

# 2) base64 로 인코딩하여 RELEASE_KEYSTORE_BASE64 값으로 등록
base64 -w0 release.jks   # (macOS: base64 -i release.jks)
```

### 서명된 release 빌드 실행
- Actions 탭에서 워크플로우를 **수동 실행(Run workflow)** 하면 `build-release-signed` 잡이 동작합니다.
- Secret 이 없으면 이 잡은 자동으로 건너뜁니다(디버그 빌드는 정상 수행).
- 산출물: `gptgongjakso-release-apk`.

> **보안:** 이 저장소의 소스에는 서명키/비밀번호가 포함되어 있지 않습니다.
> `app/build.gradle` 는 위 환경변수가 모두 존재하고 키스토어 파일이 실제로 있을 때만 서명 설정을 활성화합니다.

## 4. 참고
- Gradle Wrapper(8.9)와 `gradle-wrapper.jar` 는 프로젝트에 포함되어 있어 별도 Gradle 설치가 필요 없습니다.
- 최초 CI 빌드는 의존성 다운로드로 수 분이 걸릴 수 있습니다.
- 빌드 실패 시 `build-log` 아티팩트와 Actions 로그를 확인하세요.
