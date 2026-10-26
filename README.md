# md2docu

Markdown을 **PDF / DOCX**로 변환하고, **DOCX를 Markdown으로 역변환**하는 Spring Boot 웹 애플리케이션입니다.

## 주요 기능

- Markdown 텍스트 또는 MD/ZIP 파일을 PDF / DOCX로 변환
- URL의 Markdown/ZIP 가져오기, GitHub·GitLab 파일 링크의 Raw URL 변환
- DOCX → Markdown 역변환, 이미지 추출 및 H2 기준 챕터 분리
- 목차·소제목 번호 생성, 링크 처리 전략, 이미지 포함 옵션
- 본문·제목·코드 글자 크기, 글꼴, 줄 간격, 여백 설정 저장
- HTML 미리보기, 변환 경고, 완료 후 자동 다운로드와 재다운로드

지원 범위와 알려진 제한은 [현재 제한 사항](#현재-제한-사항)을 확인하세요. 의존성 업데이트가 기존 변환 결함까지 모두 해결한 것은 아닙니다.

## 요구사항

| 항목 | 프로젝트 기준 |
|------|---------------|
| Java | 21 이상, Java 21로 빌드·테스트 |
| Maven | 3.8 이상 |

아래 명령 예시는 **Windows PowerShell** 기준입니다. 다른 셸에서는 경로와 줄 연결 문법을 맞춰 사용하세요.

## 설치 및 실행

### 소스에서 실행

```powershell
git clone https://github.com/skax-sh-min/md2docu.git
Set-Location md2docu
mvn spring-boot:run
```

http://localhost:8080 에서 웹 UI를 이용할 수 있습니다.

### 테스트·빌드 및 JAR 실행

```powershell
# 테스트와 실행 가능한 JAR 생성
mvn verify

# 실행
java -jar .\target\md2docu-0.1.4-SNAPSHOT.jar

# 포트 변경
java -jar .\target\md2docu-0.1.4-SNAPSHOT.jar --server.port=9090
```

JAR 이름의 버전은 [pom.xml](pom.xml)의 프로젝트 버전을 따릅니다.

## 웹 UI 사용법

1. 다음 입력 탭 중 하나를 선택합니다.
   - **MD 형식의 텍스트 입력**: Markdown 작성 및 HTML 미리보기.
   - **MD 파일 업로드**: `.md` 또는 이미지가 포함된 `.zip` 업로드.
   - **DOCX → MD**: `.docx` 역변환, 선택적으로 챕터별 분리.
   - **URL 입력**: 공개 `.md` 또는 `.zip` URL 입력.
2. PDF/DOCX 변환 옵션 또는 DOCX 역변환 옵션을 선택합니다.
3. **변환하기**를 누르면 결과 파일이 자동 다운로드됩니다.
4. 경고가 있으면 확인하고, 필요하면 결과의 **다시 받기**를 사용합니다.

웹 UI 기본 선택은 **DOCX, 이미지 포함, 목차 생성, 소제목 번호 생성**입니다. REST API의 목차·번호 기본값은 `false`로 다릅니다.

### ZIP 파일 구성

현재는 Markdown 파일을 ZIP 루트에 두는 구성을 권장합니다.

```text
archive.zip
├── document.md
└── images
    └── figure.png
```

본문에서는 `![그림](images/figure.png)`처럼 참조합니다. 여러 MD가 있으면 가장 얕은 경로의 문서 하나를 선택하며, 같은 깊이의 문서 선택은 명시적으로 보장하지 않습니다. 중첩 문서의 이미지 경로와 PDF 이미지 처리에는 알려진 제한이 있습니다.

### DOCX 역변환 결과

| 조건 | 다운로드 |
|------|----------|
| 이미지 없음, 챕터 분리 끔 | `.md` |
| 이미지 있음, 챕터 분리 끔 | Markdown과 `images` 폴더를 포함한 `.zip` |
| 챕터 분리 켬 | 표지·목차와 H2별 Markdown을 포함한 `.zip` |

챕터 분리 상태에서 H2가 없으면 단일 Markdown이 들어 있는 ZIP을 반환합니다.

### URL 입력

- GitHub의 `blob` 링크와 `gitlab.com`의 파일 링크를 Raw URL로 변환합니다.
- URL 경로가 `.md` 또는 `.zip`으로 끝나야 하며, 다운로드 최대 크기는 10MB입니다.
- HTTP 리다이렉트는 따라가지 않습니다. 리다이렉트 오류 시 최종 Raw URL을 직접 사용하세요.
- 로그인·인증 헤더가 필요한 비공개 문서 가져오기는 지원하지 않습니다.

## REST API

| 메서드 | 경로 | 용도 |
|--------|------|------|
| POST | `/api/convert/{format}/text` | JSON Markdown 텍스트 → PDF/DOCX |
| POST | `/api/convert/{format}` | multipart MD/ZIP → PDF/DOCX |
| POST | `/api/convert/{format}/url` | JSON URL → PDF/DOCX |
| POST | `/api/convert/md` | multipart DOCX → Markdown/ZIP |
| GET | `/api/download/{jobId}` | 결과 다운로드 |
| GET | `/api/convert/{jobId}/warnings` | 경고 조회 |
| POST | `/api/preview` | JSON Markdown → HTML 미리보기 |
| GET | `/api/system/info` | PDF 한글 경고 여부 |
| GET / PUT | `/api/settings` | 출력 설정 조회·저장 |
| POST | `/api/settings/reset` | 기본 출력 설정으로 저장·복원 |
| GET | `/api/settings/fonts` | 설정 경로에서 감지된 글꼴 패밀리 |

`{format}`은 `pdf` 또는 `docx`입니다.

### 텍스트 변환과 다운로드

```powershell
$body = @{
    markdown = "# 제목`n`n내용입니다."
    pageSize = "A4"
    includeImages = $true
    linkStrategy = "keep"
    generateToc = $false
    numberHeadings = $false
} | ConvertTo-Json

$result = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8080/api/convert/pdf/text" `
    -ContentType "application/json; charset=utf-8" `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body))

Invoke-WebRequest `
    -Uri ("http://localhost:8080" + $result.downloadUrl) `
    -OutFile $result.fileName
```

변환 응답:

```json
{
  "jobId": "31bfcf55-...",
  "downloadUrl": "/api/download/31bfcf55-...",
  "fileName": "document.pdf",
  "warnings": []
}
```

### 파일 업로드

Windows PowerShell의 `curl` 별칭 대신 실행 파일인 `curl.exe`를 사용합니다.

```powershell
# MD → DOCX
curl.exe -X POST http://localhost:8080/api/convert/docx `
    -F "file=@.\document.md" `
    -F "pageSize=A4" `
    -F "linkStrategy=warn"

# ZIP → PDF
curl.exe -X POST http://localhost:8080/api/convert/pdf `
    -F "file=@.\archive.zip"

# DOCX → Markdown, 챕터 분리 ZIP
curl.exe -X POST http://localhost:8080/api/convert/md `
    -F "file=@.\source.docx" `
    -F "splitByChapter=true"
```

### URL 변환

```powershell
$body = @{
    url = "https://github.com/skax-sh-min/md2docu/blob/main/README.md"
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8080/api/convert/docx/url" `
    -ContentType "application/json; charset=utf-8" `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

저장소의 실제 브랜치 이름에 맞춰 URL을 변경하세요.

### 변환 옵션

| 옵션 | API 기본값 | 설명 |
|------|------------|------|
| `pageSize` | `A4` | `A4`, `LETTER` |
| `includeImages` | `true` | 이미지 임베드 여부 |
| `linkStrategy` | `keep` | `keep`, `warn`, `ignore`의 소문자 값 사용 |
| `remoteImageTimeout` | `5000` | 현재 multipart 업로드에서만 입력값 반영. 1000~30000ms로 제한 |
| `generateToc` | `false` | 목차 생성 |
| `numberHeadings` | `false` | H2~H6 소제목 번호 생성 |

텍스트·URL API는 현재 `remoteImageTimeout` 입력을 반영하지 않고 기본값을 사용합니다. 미리보기는 `markdown`, `numberHeadings`를 받으며 최종 페이지 레이아웃을 재현하지 않습니다.

| 링크 전략 | 현재 동작 및 제한 |
|-----------|------------------|
| `keep` | 외부 HTTP(S) 링크 유지. 로컬 링크는 DOCX에서 대상이 사라지는 제한이 있음 |
| `warn` | 로컬 링크에 첨부 미포함 경고를 표시하는 전략. PDF 정제 경로에서는 경고 누락 가능 |
| `ignore` | 링크 기능 제거. DOCX의 링크 내부 이미지 보존은 아직 미해결 |

로컬 링크를 유지하는 것과 파일 자체를 결과물에 첨부하는 것은 다릅니다. 첨부파일 자동 포함 기능은 없습니다.

## 출력 설정

상단 **출력 설정**에서 글꼴, 본문·코드·H1~H6 크기, 줄 간격, 상하좌우 여백을 저장합니다.

- 저장 위치: Java `user.home` 아래 `.md2docu/settings.json`. Windows에서는 일반적으로 `%USERPROFILE%\.md2docu\settings.json`입니다.
- 서버 계정의 설정 하나를 공유하므로 여러 사용자가 접속하면 같은 설정을 사용합니다.
- 모달의 **기본값으로**는 입력 폼만 변경합니다. **저장**을 눌러야 반영됩니다.
- `POST /api/settings/reset`은 기본값을 즉시 저장합니다.
- 설정 파일이 없으면 기본값을 사용합니다. 손상·읽기 실패는 기본값으로 조용히 대체하지 않고 HTTP 500 오류와 서버 로그로 알립니다.
- 손상된 파일은 먼저 백업한 뒤 수정하거나 설정 초기화 API로 복원하세요.

일부 설정의 PDF/DOCX 반영 차이는 [PLAN.md](PLAN.md)의 B07을 참고하세요.

## 크기 제한과 결과 보관

| 항목 | 기본값 | 설정 키 또는 정책 |
|------|--------|-------------------|
| 업로드 파일 최대 크기 | 200MB | `spring.servlet.multipart.max-file-size` |
| multipart 요청 최대 크기 | 210MB | `spring.servlet.multipart.max-request-size` |
| ZIP 압축 해제 최대 크기 | 350MB | `app.zip.max-extract-bytes` |
| URL 다운로드 최대 크기 | 10MB | 현재 코드 상수 |
| 결과 보관 TTL | 3600초 | `app.convert.temp-expiry-seconds` |
| 결과 보관 개수 | 최대 500개를 목표로 퇴출 | 메모리 보관, 총 바이트 용량 제한은 없음 |

```powershell
java -jar .\target\md2docu-0.1.4-SNAPSHOT.jar `
    --spring.servlet.multipart.max-file-size=200MB `
    --spring.servlet.multipart.max-request-size=210MB `
    --app.zip.max-extract-bytes=367001600
```

결과는 메모리에 보관하므로 **서버 재시작 시 사라집니다**. 한도에 도달하면 TTL 이전에도 오래된 결과가 제거될 수 있습니다. 만료 정리는 1시간 간격이며 조회 시 TTL을 검사하지 않아 정확히 1시간 동안만 접근 가능하다고 보장하지 않습니다.

## 이미지와 한글 폰트

- 로컬 이미지는 ZIP과 함께 업로드합니다. 서버의 임의 절대 경로를 읽는 기능은 아닙니다.
- `/images/fig.png` 형식은 ZIP 루트 기준 참조로 처리합니다.
- 원격 HTTP(S) 이미지와 Base64 이미지 해석 경로가 있으나 형식 및 출력 방식별 제한이 있습니다.
- 이미지 실패는 가능한 경우 플레이스홀더와 `warnings`로 보고하지만, 현재 일부 누락 경로에는 경고도 빠질 수 있습니다.

PDF 글꼴은 [application.properties](src/main/resources/application.properties)의 `app.pdf.font.paths`에 지정한 `경로|패밀리명` 목록에서 존재하는 파일을 감지합니다. Windows 맑은 고딕과 Linux Noto Sans CJK 등의 기본 후보가 있지만 서버 환경에 맞는 실제 경로를 설정해야 합니다.

```powershell
java -jar .\target\md2docu-0.1.4-SNAPSHOT.jar `
    "--app.pdf.font.paths=C:\Windows\Fonts\malgun.ttf|Malgun Gothic"
```

글꼴 파일은 애플리케이션에 포함하지 않습니다. 글꼴 미설치·등록 실패·글리프 미지원 시 한글이 깨질 수 있습니다. 현재 UI의 한글 경고는 OS 기준 추정이며 실제 글꼴 지원 검사를 의미하지 않습니다.

## 기술 스택과 의존성 업데이트

**2026-10-26 확인 기준**이며, Maven Central의 정식 릴리스와 Bootstrap의 npm `latest`를 기준으로 업데이트했습니다. RC·베타·스냅샷은 적용하지 않았습니다.

| 역할 | 라이브러리 | 적용 버전 |
|------|------------|-----------|
| 웹 프레임워크 | [Spring Boot](https://spring.io/projects/spring-boot) | 4.1.2 |
| Spring 기반 | Spring Framework | 7.0.10, Boot 관리 |
| JSON | Jackson core/databind | 3.1.7, Boot 관리 |
| Markdown | [flexmark-java](https://github.com/vsch/flexmark-java) | 0.64.8 |
| PDF | [openhtmltopdf community fork](https://github.com/openhtmltopdf/openhtmltopdf) | 1.1.100, PDFBox 3.0.8 |
| DOCX | [Apache POI](https://poi.apache.org/) | 6.0.0 |
| HTML | [jsoup](https://jsoup.org/) | 1.23.2 |
| 파일 I/O | Apache Commons IO | 2.22.0 |
| XSLT | Xalan / Serializer | 2.7.3 |
| 코드 생성 | [Lombok](https://projectlombok.org/) | 1.18.48, Boot 관리 |
| 로그 API·브리지 | Log4j API / Log4j-to-SLF4J | 2.26.1 |
| UI | [Bootstrap](https://getbootstrap.com/) | 5.3.8, CDN |

업데이트 정책:

- Spring Boot가 관리하는 전이 의존성과 Maven 플러그인은 기본적으로 Boot BOM·부모 POM을 따릅니다.
- Log4j는 POI 6이 선언한 버전보다 낮아지지 않도록 `log4j2.version`으로 맞췄습니다.
- PDF 모듈 두 개는 `openhtmltopdf.version` 하나로 버전을 맞춥니다.
- flexmark의 구형 PDF 변환 모듈 제외와 Xalan/Serializer 명시 버전은 유지합니다.
- Jackson 3는 `tools.jackson` 패키지를 사용하지만 `jackson-annotations`는 공식 정책에 따라 2.x의 `com.fasterxml.jackson.annotation`을 사용합니다.
- Spring Boot 4에 맞춰 `spring-boot-starter-webmvc`와 `spring-boot-starter-webmvc-test`로 전환했습니다.

확인 출처:

- [Spring Boot Maven metadata](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml)
- [Apache POI Maven metadata](https://repo.maven.apache.org/maven2/org/apache/poi/poi-ooxml/maven-metadata.xml)
- [openhtmltopdf 릴리스](https://github.com/openhtmltopdf/openhtmltopdf/releases)
- [jsoup 1.23.2 릴리스](https://jsoup.org/news/release-1.23.2)
- [Lombok 변경 이력](https://projectlombok.org/changelog)
- [Bootstrap npm latest](https://registry.npmjs.org/bootstrap/latest)

## 검증

```powershell
# 전체 호환성 테스트와 패키징
mvn verify

# 특정 테스트만 실행
mvn "-Dtest=DependencyCompatibilityTest,UserSettingsServiceTest" test

# 실제 해석된 의존성 버전 확인
mvn dependency:list
```

2026-10-26 기준 **21개 테스트 통과**:

- Spring Boot 4 애플리케이션 컨텍스트, MVC·Thymeleaf·JSON 응답
- 실제 PDF 생성 및 위치 정렬한 텍스트 추출
- DOCX 생성·다운로드, 표·하이퍼링크·ZIP 이미지 임베드
- DOCX → Markdown, 이미지 추출 ZIP, 챕터 분리
- Jackson 3 설정 저장·로드·초기화·기존 JSON 속성 호환
- 잘못된 입력과 설정 읽기·쓰기 실패의 오류 응답

테스트는 실제 사용자 설정 대신 메모리 설정과 임시 파일을 사용합니다. 원격 서버 다운로드, 실제 Word/LibreOffice의 표시, 한글 글꼴 조합, 부하 성능까지 검증한 것은 아닙니다.

## 현재 제한 사항

의존성 업데이트와 별개로 다음 개선 과제가 남아 있습니다.

- PDF 전처리에서 로컬/Base64 이미지 주소와 목차·로컬 링크 주소가 제거될 수 있습니다.
- ZIP 하위 폴더 문서의 상대 이미지 경로가 ZIP 루트 기준으로 해석됩니다.
- H1 없는 문서의 목차 대상·번호, 목차를 켰을 때 제목 인라인 서식에 문제가 있습니다.
- 생성 DOCX의 제목·목록은 의미 구조가 부족해 왕복 변환 시 구조를 잃을 수 있습니다.
- DOCX 역변환의 혼합 Run·줄바꿈·탭·특수문자, 링크 내부 이미지 보존에 제한이 있습니다.
- 이미지 타입 추정과 일부 실패 경고, 설정의 형식별 반영, API 옵션 처리에 차이가 있습니다.
- 미리보기는 최종 출력과 같지 않으며 응답 순서 역전 및 오류 표시 개선이 필요합니다.
- 결과 만료·용량·동시성 정책과 실제 글꼴 기반 경고는 추가 개선이 필요합니다.

원인·수정 방향·완료 기준은 [PLAN.md](PLAN.md)에 정리되어 있습니다.

## 라이선스

프로젝트 라이선스는 [LICENSE](LICENSE), 사용 라이브러리 고지는 [NOTICE](NOTICE)를 확인하세요.
