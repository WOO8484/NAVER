# 릴리즈 서명 설정 가이드 — v1.1.0

## 1. 키스토어 생성 (최초 1회만)
```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 \
  -alias gptgongjakso
```
비밀번호와 별칭을 반드시 별도의 안전한 곳(비밀번호 관리자 등)에 기록해 두세요. **이 키를 분실하면
같은 서명으로 업데이트를 배포할 수 없습니다.**

## 2. GitHub Secrets 등록
```bash
base64 -w0 release.jks   # 출력 결과를 ANDROID_KEYSTORE_BASE64 Secret 값으로 등록
```
- `ANDROID_KEYSTORE_BASE64` : 위 base64 결과
- `ANDROID_KEYSTORE_PASSWORD` : 키스토어 비밀번호
- `ANDROID_KEY_ALIAS` : 위에서 만든 별칭(예: `gptgongjakso`)
- `ANDROID_KEY_PASSWORD` : 키 비밀번호(키스토어 비밀번호와 다를 수 있음)

키스토어 파일(`release.jks`)과 비밀번호는 **절대 소스/저장소/ZIP/로그에 포함하지 않습니다.**
`release.yml` 워크플로우는 Secrets에서만 값을 읽고, 실행이 끝나면 디코딩한 키스토어 파일을 삭제합니다.

## 3. 로컬에서 서명된 release 빌드(선택)
```bash
export RELEASE_KEYSTORE_PATH=/absolute/path/to/release.jks
export RELEASE_KEYSTORE_PASSWORD=****
export RELEASE_KEY_ALIAS=****
export RELEASE_KEY_PASSWORD=****
./gradlew assembleRelease
```
`app/build.gradle` 는 위 4개 환경변수와 키스토어 파일이 **모두** 있을 때만 서명을 적용합니다. 하나라도
없으면 release는 미서명으로 빌드되며, 이는 정상 동작입니다(가짜 서명/기존 APK 재사용 없음).

## 4. 장기 업데이트 원칙
- 패키지명(`com.gptgongjakso.naverwriterhelper`)과 서명 키를 절대 바꾸지 않아야 기존 설치 위에
  업데이트 설치가 가능합니다.
- `versionCode` 는 배포할 때마다 증가시켜야 합니다(현재 v1.1.0 = 110).

## 5. 검증 상태
서명 조건부 로직(`app/build.gradle`) 자체는 정적 검토로 확인했으나(`PASS-정적검증`), 실제 Secrets를
등록한 GitHub Actions 실행과 서명된 APK 설치 확인은 이 대화에서 진행하지 못했다(`미실행-환경제한`
— Secrets/네트워크 필요).
