# md2docu

Markdown을 **PDF / DOCX**로 변환하고 **DOCX를 Markdown으로 역변환**하는 Spring Boot 웹 애플리케이션입니다.

## 주요 기능과 문서

- Markdown 텍스트·MD/ZIP·공개 URL → PDF/DOCX
- DOCX → Markdown·이미지 ZIP·H2 기준 챕터 분리
- 목차·제목 번호·링크/이미지 옵션, 출력 설정, HTML 미리보기와 변환 경고

| 문서 | 내용 |
|------|------|
| [README.md](README.md) | 소스 빌드·테스트·GitHub 릴리즈·기본 실행 및 실행 설정 개요 |
| [MANUAL.md](MANUAL.md) | 운영 점검·백업·복구, 실행 설정 상세, UI/API·변환 옵션·글꼴·제한 |
| [PLAN.md](PLAN.md) | 완료 작업 요약과 남은 검증·개선 과제 |
| [NOTICE](NOTICE) | 사용 라이브러리 고지 |

## 개발 환경

| 항목 | 기준 |
|------|------|
| Java | 21 이상, Java 21로 빌드·테스트 |
| Maven | 3.8 이상, 모든 Maven 명령은 `mvn` 사용 |
| Node.js | JavaScript 테스트용, 22 이상 권장. 기본 테스트 러너 사용, npm 설치 불필요 |
| GitHub CLI | CLI 릴리즈 절차를 사용할 때만 필요. 웹 UI로 대체 가능 |

명령은 **Bash를 우선**으로 안내하며 프로젝트 루트에서 실행합니다. 셸별 문법이 다른 경우 Bash 다음에 Windows PowerShell 예제를 제공합니다. 공통 명령은 한 번만 표기합니다. Bash 예제는 Linux/macOS 기준이며, Windows의 Git Bash·WSL에서는 Java·Maven·파일 경로를 해당 실행 환경에 맞추세요.

**Bash**

```bash
git clone https://github.com/skax-sh-min/md2docu.git
cd md2docu
java -version
mvn -version
```

**PowerShell**

```powershell
git clone https://github.com/skax-sh-min/md2docu.git
Set-Location md2docu
java -version
mvn -version
```

## 소스코드 빌드

**Bash / PowerShell 공통**

```bash
# Java 전체 테스트와 실행 가능한 JAR 생성
mvn clean verify
```

현재 프로젝트 버전은 `0.1.4-SNAPSHOT`이며 결과는 `target/md2docu-0.1.4-SNAPSHOT.jar`입니다. JAR 이름은 [pom.xml](pom.xml)의 프로젝트 버전을 따릅니다. Spring Boot 재패키징 결과인 `.jar`를 사용하고 `.jar.original`을 배포하지 마세요.

## 테스트

**Bash / PowerShell 공통**

```bash
# Java 전체 회귀 검증 및 패키징
mvn verify

# JavaScript 회귀 검증: Maven과 별도 실행
node --test src/test/js/preview.test.cjs src/test/js/pdf-environment.test.cjs

# Markdown 목차·제목 링크·번호
mvn "-Dtest=MarkdownServiceTest" test

# DOCX 구조·코드 공백·인용·링크·역변환
mvn "-Dtest=DocxStructureTest,DocxLinksTest,DocxToMarkdownConverterTest" test

# PDF 자원 처리·글꼴·환경 API
mvn "-Dtest=PdfConverterTest,FontServiceTest,PdfFontsTest,PdfEnvironmentApiTest" test

# 이미지 경로·형식·실제 출력
mvn "-Dtest=ImageResolverTest,ImageResolverFormatsTest,ImageConversionTest" test

# API·입력·실제 다운로드 시간·업로드 한도
mvn "-Dtest=ConversionApiTest,ConvertServiceInputTest,ConvertServiceDownloadTest,MultipartLimitTest" test

# 설정 저장·API·실제 출력 설정
mvn "-Dtest=UserSettingsServiceTest,SettingsControllerTest,OutputSettingsTest" test

# 보관 TTL·용량·동시 다운로드
mvn "-Dtest=ConversionResultStoreTest,ConversionRetentionApiTest" test

# 실제 MVC·템플릿·다운로드 호환성
mvn "-Dtest=DependencyCompatibilityTest" test
```

PowerShell에서는 점이 포함된 Maven `-D` 인자를 따옴표로 감싸세요.

### 한글 글꼴 테스트

한글 출력 통합 테스트는 설치된 글꼴을 사용하며 글꼴 바이너리를 저장소에 포함하지 않습니다. 기본 Windows 경로는 맑은 고딕 보통·굵은 TTF와 굴림 TTC입니다. 다른 환경에서는 사용·임베딩 권한이 있는 TrueType 글꼴 경로를 지정하세요.

**Bash:** 다음 경로는 예제입니다. 설치된 TrueType TTF·TTC의 실제 경로로 모두 변경하세요.

```bash
mvn "-Dtest=PdfFontsTest" \
    "-Dtest.pdf.korean.regular=/usr/share/fonts/truetype/nanum/NanumGothic.ttf" \
    "-Dtest.pdf.korean.bold=/usr/share/fonts/truetype/nanum/NanumGothicBold.ttf" \
    "-Dtest.pdf.korean.collection=/path/to/korean-collection.ttc" \
    "-Dtest.pdf.render-preview=true" test
```

**PowerShell**

```powershell
mvn "-Dtest=PdfFontsTest" `
    "-Dtest.pdf.korean.regular=C:\Windows\Fonts\malgun.ttf" `
    "-Dtest.pdf.korean.bold=C:\Windows\Fonts\malgunbd.ttf" `
    "-Dtest.pdf.korean.collection=C:\Windows\Fonts\gulim.ttc" `
    "-Dtest.pdf.render-preview=true" test
```

해당 파일이 없으면 한글 출력 통합 테스트 두 건은 명시적으로 건너뜁니다. 릴리즈 시 실행·건너뜀 결과와 사용 글꼴을 기록하세요. 옵션 `test.pdf.render-preview=true`는 확인용 PNG를 빌드 출력에 생성합니다. 운영 글꼴 설정은 [매뉴얼](MANUAL.md#pdf-글꼴-검사와-한글-안내)을 참고하세요.

### 검증 현황과 범위

2026-10-27 추가 결함 수정 후 **Java 653개, JavaScript 66개**가 실패·오류·건너뜀 없이 통과했고 `mvn verify`로 실행 JAR을 생성했습니다. 이는 해당 검증 시점의 결과이며 이후 변경은 다시 검증해야 합니다.

- Java: 호환성 103, 변환 API 65, 환경 API 10, 글꼴 검사 12, 한글 PDF 7, 입력 검증 57, 실제 URL 다운로드 6, 업로드 한도 4, 결과 보관 31, 보관 API 13, DOCX 구조 40, 링크 52, 역변환 42, Markdown 37, PDF 49, 설정 저장 23, 설정 API 5, 출력 설정 7, 이미지 경로 16, 형식/한도 53, 출력 21.
- JavaScript: 실제 템플릿 핸들러의 미리보기 요청 경쟁·상태·재시도 44개, PDF 환경 안내 22개. Maven 합계에 포함하지 않습니다.
- 목차·번호 옵션별 링크 대상, 실제 PDF 대상 위치, DOCX OOXML·이미지 바이트/폭, Unicode 챕터 ZIP, 15초 정지/저속 HTTP 취소, 보관 경계·동시성·설정 실패를 검증합니다.
- 테스트는 메모리 설정·임시 파일과 HTTP 대역/로컬 서버를 사용합니다. 실제 사용자 설정을 정상 저장하는 방식으로 테스트하지 않습니다.
- 별도로 설정 모달 10개, 변환/입력 오류 처리 16개, 보관 기한 표시 9개 핸들러 시나리오를 확인한 이력이 있습니다. 이들은 위 66개의 상시 Node 테스트 수에 포함하지 않습니다.
- 한글 추출·임베딩·래스터와 대표 이미지는 확인했지만 실제 브라우저 화면·Word/LibreOffice 동작·모든 글꼴/문자 조합·외부 서버·부하까지 인증한 것은 아닙니다.

## 실행

### 개발 실행

**Bash / PowerShell 공통**

```bash
mvn spring-boot:run
```

### 빌드 JAR 실행

**Bash**

```bash
java -jar ./target/md2docu-0.1.4-SNAPSHOT.jar
```

**PowerShell**

```powershell
java -jar .\target\md2docu-0.1.4-SNAPSHOT.jar
```

기본 접속 주소는 http://localhost:8080 입니다. Java 설치가 필요하며 브라우저에서 입력·변환·다운로드를 사용할 수 있습니다.

### 실행 설정 개요

**Bash**

```bash
# 포트 변경 예제
java -jar ./target/md2docu-0.1.4-SNAPSHOT.jar --server.port=9090

# 기본값을 유지하며 운영 외부 설정 추가
java -jar ./target/md2docu-0.1.4-SNAPSHOT.jar \
    "--spring.config.additional-location=file:/opt/md2docu/config/operator.properties"
```

**PowerShell**

```powershell
# 포트 변경 예제
java -jar .\target\md2docu-0.1.4-SNAPSHOT.jar --server.port=9090

# 기본값을 유지하며 운영 외부 설정 추가
java -jar .\target\md2docu-0.1.4-SNAPSHOT.jar `
    "--spring.config.additional-location=file:C:/md2docu/config/operator.properties"
```

| 설정 구분 | 설정 위치·반영 | 상세 |
|-----------|----------------|------|
| 포트·바인딩·JVM·외부 설정·로그 | JVM/실행 인자·외부 properties, 재시작 | [실행 설정 상세](MANUAL.md#실행-설정-상세) |
| 업로드·ZIP 한도·결과 TTL/용량 | `application.properties` 또는 실행 인자, 재시작 | [크기 제한과 결과 보관](MANUAL.md#크기-제한과-결과-보관) |
| PDF 글꼴 후보 | `app.pdf.font.paths`, 재시작·시작 검사 | [글꼴 설정](MANUAL.md#pdf-글꼴-검사와-한글-안내) |
| 글자 크기·줄 간격·여백 | UI/API, 서버 계정별 설정 파일, 다음 변환부터 | [출력 설정](MANUAL.md#출력-설정) |
| 변환별 옵션 | UI/API 요청마다 지정 | [변환 옵션](MANUAL.md#변환-옵션) |

외부 설정 파일은 실행 전에 준비하세요. 서버 계정·백업·재시작·장애 대응과 각 설정 값의 의미·제한은 [운영자 매뉴얼](MANUAL.md)에 정리했습니다. 변환 결과는 임시 메모리 보관이므로 재시작 시 사라집니다.

## GitHub 릴리즈

현재 저장소에는 GitHub Actions 릴리즈 워크플로가 없으므로 **태그를 푸시하는 것만으로 빌드·테스트·릴리즈 업로드가 자동 실행되지 않습니다.** 아래는 수동 배포 절차이며 명령을 문서화한 것이지 실제 릴리즈를 게시한 것은 아닙니다.

### 1. 버전과 배포 커밋 확정

1. 배포할 변경을 리뷰·커밋하고 `git status --short`로 작업 트리가 깨끗한지 확인합니다.
2. 공식 릴리즈는 [pom.xml](pom.xml)의 프로젝트 버전에서 `-SNAPSHOT`을 제거합니다. 아래 `0.1.4`는 예시이며 기존 태그와 중복되지 않는 실제 배포 버전을 선택하세요.
3. 버전·JAR 실행 예제·변경 사항·[NOTICE](NOTICE)와 운영 문서를 갱신하고 버전 변경을 커밋합니다. 부모 Spring Boot 버전과 프로젝트 버전을 혼동하지 마세요.
4. 릴리즈 노트에는 수정/기능, 호환성·설정 변경, 검증/건너뜀 결과, 알려진 제한과 운영 시 주의사항을 준비합니다.

### 2. 깨끗한 소스에서 검증·배포 파일 준비

**Bash:** 하위 셸에서 실행해 검증·체크섬 생성 실패 시 이후 작업을 중단합니다. Linux는 `sha256sum`, macOS는 `shasum`을 사용합니다.

```bash
(
    set -e
    mvn clean verify
    node --test src/test/js/preview.test.cjs src/test/js/pdf-environment.test.cjs

    version="0.1.4" # pom.xml에 확정한 실제 버전
    jar="target/md2docu-$version.jar"
    if [ ! -f "$jar" ]; then
        printf 'Release JAR not found: %s\n' "$jar" >&2
        exit 1
    fi
    (
        cd target
        if command -v sha256sum >/dev/null 2>&1; then
            sha256sum "md2docu-$version.jar" > SHA256SUMS.txt
        elif command -v shasum >/dev/null 2>&1; then
            shasum -a 256 "md2docu-$version.jar" > SHA256SUMS.txt
        else
            printf 'sha256sum or shasum is required.\n' >&2
            exit 1
        fi
    )
)
```

**PowerShell**

```powershell
mvn clean verify
if ($LASTEXITCODE -ne 0) { throw "Java 검증 실패" }
node --test src\test\js\preview.test.cjs src\test\js\pdf-environment.test.cjs
if ($LASTEXITCODE -ne 0) { throw "JavaScript 검증 실패" }

$version = "0.1.4" # pom.xml에 확정한 실제 버전
$jar = ".\target\md2docu-$version.jar"
if (!(Test-Path -LiteralPath $jar)) { throw "배포 JAR 없음: $jar" }
$hash = Get-FileHash -LiteralPath $jar -Algorithm SHA256
"$($hash.Hash.ToLowerInvariant())  md2docu-$version.jar" |
    Set-Content -LiteralPath ".\target\SHA256SUMS.txt" -Encoding ASCII
```

별도 실행 환경에서 이 JAR을 실행해 [운영 기동 점검](MANUAL.md#기동-점검)과 대표 문서 변환을 수행하세요. 릴리즈에는 실행 가능한 `.jar`, `SHA256SUMS.txt`, [MANUAL.md](MANUAL.md), [LICENSE](LICENSE), [NOTICE](NOTICE)를 첨부합니다. `.jar.original`, 사용자 설정·로그·원본 문서·사용 권한이 없는 글꼴을 첨부하지 마세요.

### 3. 검증한 커밋에 태그 생성·푸시

**Bash**

```bash
(
    set -e
    git status --short
    git log -1 --oneline
    git tag -a v0.1.4 -m "Release v0.1.4"
    git push origin HEAD
    git push origin v0.1.4
)
```

**PowerShell**

```powershell
git status --short
git log -1 --oneline
git tag -a v0.1.4 -m "Release v0.1.4"
if ($LASTEXITCODE -ne 0) { throw "태그 생성 실패" }
git push origin HEAD
if ($LASTEXITCODE -ne 0) { throw "커밋 푸시 실패" }
git push origin v0.1.4
if ($LASTEXITCODE -ne 0) { throw "태그 푸시 실패" }
```

태그도 실제 버전에 맞춰 변경하세요. 빌드한 소스와 태그의 커밋이 같아야 합니다. 기존 공개 태그나 자산을 덮어쓰지 말고 수정 배포는 새 버전을 사용하세요.

### 4. GitHub에서 릴리즈 게시

**웹 UI:** [Releases](https://github.com/skax-sh-min/md2docu/releases) → **Draft a new release**에서 이미 푸시한 태그를 선택하고 제목·릴리즈 노트를 입력합니다. 준비한 자산을 업로드한 뒤 버전·태그·체크섬·파일을 확인하고 게시하세요. `-SNAPSHOT` 같은 개발 빌드를 배포한다면 공식 안정 릴리즈가 아니라 prerelease임을 명확히 표시하세요.

**GitHub CLI 대안:** `gh`를 별도 설치하고 쓰기 권한이 있는 계정으로 인증합니다.

**Bash**

```bash
gh auth login &&
gh release create v0.1.4 \
    ./target/md2docu-0.1.4.jar ./target/SHA256SUMS.txt \
    ./MANUAL.md ./LICENSE ./NOTICE \
    --repo skax-sh-min/md2docu \
    --verify-tag --draft --title "md2docu 0.1.4" --generate-notes
```

**PowerShell**

```powershell
gh auth login
gh release create v0.1.4 `
    .\target\md2docu-0.1.4.jar .\target\SHA256SUMS.txt `
    .\MANUAL.md .\LICENSE .\NOTICE `
    --repo skax-sh-min/md2docu `
    --verify-tag --draft --title "md2docu 0.1.4" --generate-notes
```

`--verify-tag`는 원격 태그가 없으면 실패시켜 기본 브랜치의 다른 커밋으로 자동 태그가 만들어지는 것을 방지합니다. `--draft`는 초안만 만들므로 웹에서 자동 생성 노트를 보완하고 자산을 확인한 뒤 게시해야 합니다. 토큰은 문서·명령 이력·소스에 기록하지 마세요. 옵션은 [GitHub CLI 공식 문서](https://cli.github.com/manual/gh_release_create)를 참고하세요.

### 5. 게시 후 확인

- 릴리즈에서 JAR과 체크섬을 같은 폴더에 새로 내려받아 비교합니다. Bash에서는 그 폴더에서 `sha256sum -c SHA256SUMS.txt`(Linux) 또는 `shasum -a 256 -c SHA256SUMS.txt`(macOS)를 실행하고, PowerShell에서는 `Get-FileHash -Algorithm SHA256` 결과를 체크섬 파일과 비교합니다. GitHub가 자동 생성한 Source code ZIP은 실행 JAR이 아닙니다.
- 내려받은 JAR로 UI·환경 정보·대표 PDF/DOCX 변환·다운로드를 확인합니다.
- 태그·버전·검증 결과와 운영 변경점을 기록하고 [매뉴얼의 교체·롤백 절차](MANUAL.md#백업과-교체)를 따릅니다.

## 기술 스택과 의존성 정책

기존 라이브러리는 2026-10-26, SVG/WebP 라이브러리는 2026-10-27 확인 기준입니다. 자세한 직접 의존성과 제외 규칙은 [pom.xml](pom.xml)을 기준으로 합니다.

| 역할 | 버전 |
|------|------|
| Spring Boot / Framework | 4.1.2 / 7.0.10 |
| Jackson core/databind | 3.1.7 |
| flexmark-java | 0.64.8 |
| openhtmltopdf / PDFBox | 1.1.100 / 3.0.8 |
| Apache POI | 6.0.0, 전체 OOXML 스키마 |
| Batik / XML Graphics Commons | 1.20 / 2.12 |
| TwelveMonkeys WebP | 3.15.3 |
| jsoup / Commons IO | 1.23.2 / 2.22.0 |
| Xalan / Serializer | 2.7.3 |
| Lombok / Log4j API·브리지 | 1.18.48 / 2.26.1 |
| Bootstrap | 5.3.8, CDN |

Boot BOM·부모 POM의 전이 의존성/플러그인 관리를 기본으로 합니다. Log4j는 POI 6 요구 버전보다 낮아지지 않도록 정렬하며 PDF·POI·Batik 모듈은 각 공통 버전 속성으로 맞춥니다. 구형 PDF/Batik 전이 의존성, `poi-ooxml-lite` 중복 스키마는 제외합니다. Jackson 3는 `tools.jackson`을 사용하되 annotations는 공식 2.x 패키지를 유지합니다. 정식 안정 버전을 확인해 갱신하고 아래 명령으로 해석 결과를 점검하세요.

**Bash / PowerShell 공통**

```bash
mvn dependency:list
```

## 현재 제한과 향후 개발 과제

현재 제한을 바탕으로 다음 후속 개발·검증 과제를 검토할 수 있습니다. 구체적인 범위·우선순위·완료 기준은 [PLAN.md](PLAN.md)를 기준으로 하며, 아래 항목이 모두 진행 중이거나 구현이 확정된 것은 아닙니다.

- **DOCX 변환 지원 확대와 표시 검증:** 미지원 편집 구조·병합 레이아웃·페이지 배치와 북마크 대상의 역변환 지원을 검토합니다. 생성한 링크·북마크의 실제 Word/LibreOffice 동작은 별도 수동 확인이 필요합니다.
- **최종 문서 기반 미리보기:** 현재 HTML 미리보기는 최종 페이지 배치·출력 설정을 재현하지 않습니다. 실제 PDF/DOCX 기반 미리보기는 별도 개선 제안입니다.
- **결과 영속화와 자원 사용 제어:** 결과는 임시 메모리 보관이며 재시작 복구·디스크 저장·전체 변환/다운로드 메모리와 동시 실행 수 제한은 제공하지 않습니다. 영속 저장과 전체 처리 자원 제어를 후속 과제로 검토합니다.
- **PDF 글꼴 검증 범위 확대:** 현재 검사는 설정 후보·기본 한글 샘플 범위입니다. 모든 글리프·사용자가 선택한 글꼴·서체 조합과 최종 표시를 인증하지 않으므로 추가 검증이 필요합니다.

운영 중 적용되는 제한과 대응 방법은 [MANUAL.md](MANUAL.md#현재-제한-사항)를 참고하세요.

## 라이선스

프로젝트 라이선스는 [LICENSE](LICENSE), 사용 라이브러리 고지는 [NOTICE](NOTICE)를 확인하세요.
