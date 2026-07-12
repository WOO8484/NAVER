# GitHub 업로드 순서

## 웹에서 업로드

1. GitHub에서 새 저장소를 만듭니다.
2. 이 ZIP을 PC에서 풉니다.
3. `GPT공작소_v1.1.0_GitHub업로드용` 폴더 자체가 아니라 **폴더 안의 모든 파일**을 저장소 최상위에 업로드합니다.
4. 숨김 폴더인 `.github`와 숨김 파일 `.gitignore`, `.gitattributes`, `.editorconfig`도 반드시 포함합니다.
5. 기본 브랜치를 `main`으로 사용합니다.
6. 업로드 후 **Actions** 탭에서 `Android CI Build` 결과를 확인합니다.
7. 성공하면 실행 결과의 `app-debug-apk` 아티팩트를 내려받습니다.

저장소 최상위에서 다음 파일이 보여야 정상입니다.

```text
.github/
app/
gradle/
docs/
samples/
.gitignore
.gitattributes
.editorconfig
README.md
GITHUB_UPLOAD_GUIDE.md
build.gradle
gradle.properties
gradlew
gradlew.bat
settings.gradle
```

## Git 명령으로 업로드

```bash
git init
git branch -M main
git add .
git commit -m "GPT 공작소 Android v1.1.0"
git remote add origin <저장소 주소>
git push -u origin main
```

## 업로드 전 금지 파일 확인

```bash
git status --ignored
```

`local.properties`, 키스토어, 비밀번호, API 키가 추적 목록에 없어야 합니다.
