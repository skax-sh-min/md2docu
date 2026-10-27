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

### 목차·제목 번호 규칙

- 목차와 번호는 독립적인 옵션이며, 문서 최상위의 Markdown H2~H6 제목을 대상으로 합니다. `#` 방식과 Setext 방식(`===`, `---` 밑줄)을 지원합니다.
- 목차는 첫 H1 바로 뒤에 한 번 삽입합니다. H1이 없으면 문서 맨 앞에 삽입하며, 대상 제목이 없으면 빈 목차를 만들지 않습니다.
- H1에는 번호를 붙이지 않으며, 문서 중간의 추가 H1도 번호를 초기화하지 않습니다.
- 단계를 건너뛰면 실제 제목 계층은 유지하고 빠진 상위 번호를 `1`로 보정합니다. 예를 들어 H3부터 시작하면 `1.1.`, H2의 첫 하위 제목이 H4이면 `1.1.1.`이 됩니다.
- HTML/PDF 제목의 강조·코드·링크 등 인라인 서식은 유지하고, 목차에는 이스케이프한 일반 텍스트를 사용합니다. 생성 ID는 기존 HTML ID와 겹치지 않도록 부여합니다.
- 코드 블록, 인용문·목록 내부의 제목, 직접 작성한 HTML 제목은 목차·번호 대상으로 다시 가공하지 않습니다.
- 미리보기는 번호 옵션만 반영하며 목차는 표시하지 않습니다.

### DOCX 제목·목록 구조

- H1~H6은 `Heading1`~`Heading6` 스타일과 탐색용 outline level을 갖습니다. 설정한 제목 크기와 글꼴을 사용하며 강조·기울임·취소선·인라인 코드·외부 링크를 개별 Run으로 보존합니다.
- 순서/비순서 목록은 표시용 문자를 본문에 붙이지 않고 실제 Word numbering을 사용합니다. 혼합 중첩, 순서 목록의 시작 번호, 별개 목록의 재시작과 부모 목록의 이어짐을 보존합니다.
- DOCX 형식에 맞춰 목록은 최대 9단계까지 지원하며, 더 깊은 입력은 구조를 평탄화하지 않고 변환을 거부합니다.
- 역변환은 첫 항목의 시작 번호를 기록하고 이어지는 항목은 `1.` 마커로 정규화합니다. 중첩 들여쓰기는 해당 Markdown 마커의 폭에 맞춥니다. 인접한 별개 목록은 보이지 않는 `<!-- list break -->` 주석으로 구분합니다.
- Word 시작 번호가 Markdown의 범위인 `0`~`999999999` 밖이면 `1`로 대체하고 `UNSUPPORTED_LIST_START`를 반환합니다. 번호 정의 누락은 `LIST_NUMBERING_UNRESOLVED`, 십진수·글머리표 외 번호 형식의 십진수 대체는 `UNSUPPORTED_LIST_FORMAT`으로 알립니다.
- 생성한 DOCX도 H2를 기준으로 Markdown 챕터 ZIP을 만들 수 있습니다.

### ZIP 파일 구성

Markdown 파일은 ZIP 루트 또는 하위 폴더에 둘 수 있습니다.

```text
archive.zip
├── docs
│   ├── document.md
│   └── images
│       └── figure.png
└── images
    └── shared.png
```

위 구성의 `docs/document.md`에서는 다음 경로를 사용할 수 있습니다. PDF와 DOCX 모두 같은 규칙을 적용합니다.

| 이미지 참조 | 해석 위치 |
|-------------|-----------|
| `images/figure.png` | 문서 폴더 기준의 `docs/images/figure.png` |
| `../images/shared.png` | ZIP 내부 상위 폴더의 `images/shared.png` |
| `/images/shared.png` | ZIP 루트 기준의 `images/shared.png` |

ZIP 범위 밖으로 벗어나는 참조는 읽지 않고 `IMAGE_NOT_FOUND` 경고를 반환합니다. URL 인코딩된 파일명은 한 번만 디코딩합니다.

여러 MD가 있으면 가장 얕은 경로의 문서 하나를 선택하며, 같은 깊이의 문서 선택은 명시적으로 보장하지 않습니다. 일부 이미지 형식에는 알려진 제한이 있습니다.

### DOCX 역변환 결과

| 조건 | 다운로드 |
|------|----------|
| 이미지 없음, 챕터 분리 끔 | `.md` |
| 이미지 있음, 챕터 분리 끔 | Markdown과 `images` 폴더를 포함한 `.zip` |
| 챕터 분리 켬 | 표지·목차와 H2별 Markdown을 포함한 `.zip` |

챕터 분리 상태에서 H2가 없으면 단일 Markdown이 들어 있는 ZIP을 반환합니다.

#### 내용·서식 보존 규칙

- 같은 Run의 텍스트·이미지·텍스트를 원래 순서대로 보존합니다. 같은 이미지가 반복되면 파일은 재사용하고 본문의 각 표시 위치는 유지합니다. 하이퍼링크 안의 텍스트·이미지도 순서대로 직렬화합니다.
- 본문의 수동 줄바꿈은 Markdown hard break(줄 끝 공백 두 개와 개행)로 기록합니다. 제목·표 셀, 연속·끝 줄바꿈처럼 한 줄 표현이 필요한 경우에는 `<br />`를 사용합니다.
- 탭은 `&#9;`로 기록합니다. 탭 문자 자체는 보존하지만 Word의 탭 정지점이나 페이지 배치를 재현하는 것은 아닙니다.
- 일반 텍스트의 별표·백틱·대괄호·HTML 모양 문자열이 서식으로 바뀌지 않도록 역슬래시와 문자 참조로 이스케이프합니다. 원래 일반 텍스트였던 URL·이메일은 자동 링크로 바꾸지 않습니다.
- 같은 서식의 인접 Run은 합칩니다. 서식 경계가 맞닿거나 코드의 앞뒤 공백·표 셀 내용을 정확히 보존해야 하는 경우에는 인라인 HTML을 사용합니다. 취소선과 `Courier New` Run의 인라인 코드도 보존합니다.
- 링크 표시 문구와 주소를 구분해 처리합니다. 괄호·엔티티가 있는 주소의 의미가 달라지지 않도록 Markdown 링크 또는 HTML 앵커를 선택합니다. 챕터 표지의 제목은 일반 텍스트로 추출하고 안전한 목차 링크를 만듭니다.
- 표는 첫 행을 헤더로 하는 Markdown 표로 직렬화합니다. 셀 안의 문단 경계와 줄바꿈은 `<br />`로, 파이프와 백슬래시는 셀 구분자로 오해되지 않도록 기록합니다.
- 감지한 미지원 필드·컨트롤·병합/중첩 표 등은 가능한 표시 내용을 보존하고 `UNSUPPORTED_DOCX_CONTENT`로 알립니다. 편집 구조·병합 레이아웃·페이지/단 나눔까지 재현하지는 않습니다. 주소가 없는 링크는 표시 내용을 유지하고 `HYPERLINK_UNRESOLVED`를 반환합니다.
- 내부 링크 주소도 보존하지만 Word 북마크 대상은 재생성하지 않으며 `UNSUPPORTED_DOCX_CONTENT`로 알립니다.

#### B05 검증용 샘플

- [DOCX 원본](sample/b05-content-preservation.docx)
- [역변환 기준 Markdown](sample/b05-content-preservation.md)
- [Markdown이 참조하는 이미지](sample/images/image_1.png)

두 문서는 같은 내용을 담고 있으며, Markdown은 현재 변환기가 DOCX 원본을 실제 역변환한 결과입니다. 텍스트 사이의 반복 이미지, 수동·연속 줄바꿈, 탭, 리터럴 Markdown 문법, 인접한 서식 Run, 공백이 있는 코드, 특수문자 링크, 표 셀의 문단·파이프, 혼합 중첩 목록을 확인할 수 있습니다.

1. **DOCX → MD**에서 DOCX 원본을 업로드하고 **챕터 분리를 끈 상태**로 변환합니다.
2. 내려받은 ZIP을 풀고 Markdown 내용과 이미지 파일을 기준 샘플과 비교합니다. 이미지 표시는 총 4곳이며, 동일한 PNG 파일 하나를 재사용합니다. 본문 hard break의 줄 끝 공백 두 개는 의도된 표현입니다.
3. `09`의 DATE 필드는 저장된 표시값 `2026-10-27`만 보존하고, `10`의 내부 링크는 주소만 보존합니다. **`UNSUPPORTED_DOCX_CONTENT` 경고 2건**(`fldSimple`, `bookmark-target:b05_target`)은 예상된 결과이며, Word 북마크로의 이동은 재현하지 않습니다.

Markdown을 PDF/DOCX로 변환할 때는 로컬 이미지도 함께 전달하도록 다음과 같이 ZIP으로 묶어 업로드합니다. 프로젝트 루트에서 실행하세요.

```powershell
Compress-Archive -Path .\sample\b05-content-preservation.md, .\sample\images -DestinationPath .\b05-sample.zip -Force
```

MD 파일만 업로드하면 함께 저장된 로컬 PNG를 읽을 수 없습니다. 이 샘플은 DOCX → Markdown의 내용 보존 확인용이며, Word의 페이지 배치나 완전한 왕복 변환을 보장하지는 않습니다.

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
| `keep` | PDF는 로컬 참조 대상·외부 링크·내부 앵커를 보존. 로컬 링크는 DOCX에서 대상이 사라지는 제한이 있음 |
| `warn` | 로컬 링크에 첨부 미포함 표시와 `ATTACHMENT_NOT_FOUND` 경고를 반환. PDF는 외부 링크·내부 앵커를 유지 |
| `ignore` | 링크 기능 제거. PDF는 링크 내부 텍스트·이미지를 보존하며, DOCX의 링크 내부 이미지 보존은 아직 미해결 |

로컬 링크를 유지하는 것과 파일 자체를 결과물에 첨부하는 것은 다릅니다. 첨부파일 자동 포함 기능은 없습니다.

PDF 외부 링크는 HTTP(S)·mailto·FTP를 허용합니다. `file:`·`jar:`·실행 스킴·data 링크 및 `//host/path`처럼 프로토콜을 생략한 외부 주소는 링크를 제거하고 `UNSUPPORTED_RESOURCE` 경고를 반환합니다. 링크 내부 콘텐츠는 유지합니다.

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
| 이미지 한 개의 바이트 크기 | 50MiB (52,428,800바이트) | 로컬·HTTP(S)·Base64 공통 상수 |
| 이미지 한 개의 픽셀 수 | 6,000,000픽셀 | 너비 × 높이, SVG 출력에도 적용 |
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
- 상대 이미지 경로는 선택된 Markdown의 부모 폴더를 기준으로 해석합니다. `../`도 ZIP 범위 안에서만 허용합니다.
- `/images/fig.png` 형식은 ZIP 루트 기준 참조로 처리합니다.
- 로컬·HTTP(S)·Base64 모두 바이트 내용으로 이미지 형식을 판별합니다. 확장자가 없거나 잘못되어도 처리하며, 선언된 MIME과 HTTP Content-Type은 보조 정보로만 사용합니다.
- PNG/JPEG/GIF/BMP를 지원하고, **SVG/WebP는 PNG로 변환**해 PDF와 DOCX에 넣습니다. DOCX에는 GIF 원본을 보존하고, PDF에는 색상 손실을 피하도록 첫 프레임을 PNG로 넣습니다. WebP도 첫 프레임을 사용하며 애니메이션 재생은 보장하지 않습니다. BMP는 POI와 동일한 `image/x-ms-bmp` MIME을 사용합니다.
- SVG는 정적 도형을 래스터화합니다. 스크립트를 실행하지 않고 DTD·외부 엔티티를 거부합니다. 외부 파일·URL·임베디드 이미지·외부 글꼴 참조와 `foreignObject`는 지원하지 않으며, 필요한 리소스를 읽지 못한 SVG를 성공한 이미지로 반환하지 않습니다.
- 모든 경로에 **50MiB 및 600만 픽셀** 제한을 적용합니다. 3000×2000에 상응하는 픽셀 수 상한으로, 2000×3000 같은 세로 이미지도 허용합니다. 한도 초과 이미지를 자동 축소해 받아들이지는 않으며 경고와 플레이스홀더로 알립니다.
- DOCX 이미지는 기존 400pt 너비·550pt 높이 상한과 실제 페이지 본문·목록 들여쓰기·표 셀의 안쪽 폭을 함께 고려해 종횡비를 유지합니다. PDF도 본문 높이와 표 셀 폭 안에서 비율을 유지하며, 이미지가 들어간 표는 고정 열 배치로 폭 넘침을 방지합니다.
- 이미지 실패는 표시 위치에 `[이미지 로드 실패: ...]`를 넣고 해당 원인을 한 번 경고합니다. 긴 주소·data URI는 경고 대상과 플레이스홀더에서 최대 256자로 줄입니다. 이미지 포함 옵션을 끈 경우는 의도된 제외이므로 실패 경고를 만들지 않습니다.

PDF는 HTML 정제와 주소 검증을 유지하고, 로컬 파일명의 URL 디코딩은 PDF·DOCX의 공통 이미지 해석기에서 수행합니다. 기존 ZIP 경로 범위와 원격 이미지 주소 제한은 유지합니다.

| 경고 코드 | 의미 |
|-----------|------|
| `IMAGE_NOT_FOUND` | 로컬 파일 누락·기준 폴더 없음·허용 범위 밖 경로 |
| `IMAGE_FETCH_FAILED` | 원격 다운로드 실패·원격 주소 접근 제한 |
| `IMAGE_INVALID_DATA` | 잘못된 Base64·손상된 이미지·잘못된 SVG XML |
| `IMAGE_UNSUPPORTED_FORMAT` | TIFF 등 지원 목록 밖이거나 식별할 수 없는 형식 |
| `IMAGE_TOO_LARGE` | 50MiB 또는 600만 픽셀 초과 |
| `IMAGE_CONVERSION_FAILED` | SVG/PNG 변환 실패·차단된 SVG 리소스·미지원 SVG 내용 |
| `IMAGE_EMBED_FAILED` | DOCX 삽입 실패·이미지를 배치할 본문/셀 영역 없음 |
| `UNSUPPORTED_RESOURCE` | 지원하지 않는 리소스 주소·data URI 헤더 |

잘못된 Base64는 기존의 포괄적인 `IMAGE_FETCH_FAILED` 대신 `IMAGE_INVALID_DATA`로 보고합니다.

PDF 글꼴은 [application.properties](src/main/resources/application.properties)의 `app.pdf.font.paths`에 지정한 `경로|패밀리명` 목록에서 존재하는 파일을 감지합니다. Windows 맑은 고딕과 Linux Noto Sans CJK 등의 기본 후보가 있지만 서버 환경에 맞는 실제 경로를 설정해야 합니다.

```powershell
java -jar .\target\md2docu-0.1.4-SNAPSHOT.jar `
    "--app.pdf.font.paths=C:\Windows\Fonts\malgun.ttf|Malgun Gothic"
```

글꼴 파일은 애플리케이션에 포함하지 않습니다. 글꼴 미설치·등록 실패·글리프 미지원 시 한글이 깨질 수 있습니다. 현재 UI의 한글 경고는 OS 기준 추정이며 실제 글꼴 지원 검사를 의미하지 않습니다.

## 기술 스택과 의존성 업데이트

기존 라이브러리는 **2026-10-26**, SVG/WebP 라이브러리는 **2026-10-27 확인 기준**입니다. Maven Central의 정식 릴리스와 Bootstrap의 npm `latest`를 기준으로 업데이트했으며, RC·베타·스냅샷은 적용하지 않았습니다.

| 역할 | 라이브러리 | 적용 버전 |
|------|------------|-----------|
| 웹 프레임워크 | [Spring Boot](https://spring.io/projects/spring-boot) | 4.1.2 |
| Spring 기반 | Spring Framework | 7.0.10, Boot 관리 |
| JSON | Jackson core/databind | 3.1.7, Boot 관리 |
| Markdown | [flexmark-java](https://github.com/vsch/flexmark-java) | 0.64.8 |
| PDF | [openhtmltopdf community fork](https://github.com/openhtmltopdf/openhtmltopdf) | 1.1.100, PDFBox 3.0.8 |
| DOCX | [Apache POI](https://poi.apache.org/) | 6.0.0 |
| SVG 래스터화 | [Apache Batik](https://xmlgraphics.apache.org/batik/) | 1.20, XML Graphics Commons 2.12 |
| WebP 디코더 | [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) | 3.15.3 |
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
- Batik 모듈은 `batik.version`으로 맞추고 PDF SVG 모듈의 구형 전이 의존성을 제외합니다. TwelveMonkeys 모듈은 `twelvemonkeys.version`으로 맞춥니다.
- POI 모듈은 `poi.version`으로 맞추고, 다단계 번호 정의에 필요한 전체 스키마인 `poi-ooxml-full`을 사용합니다. 중복 스키마를 피하기 위해 `poi-ooxml-lite`는 제외합니다.
- flexmark의 구형 PDF 변환 모듈 제외와 Xalan/Serializer 명시 버전은 유지합니다.
- Jackson 3는 `tools.jackson` 패키지를 사용하지만 `jackson-annotations`는 공식 정책에 따라 2.x의 `com.fasterxml.jackson.annotation`을 사용합니다.
- Spring Boot 4에 맞춰 `spring-boot-starter-webmvc`와 `spring-boot-starter-webmvc-test`로 전환했습니다.

확인 출처:

- [Spring Boot Maven metadata](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml)
- [Apache POI Maven metadata](https://repo.maven.apache.org/maven2/org/apache/poi/poi-ooxml/maven-metadata.xml)
- [Batik Maven metadata](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/batik-transcoder/maven-metadata.xml)
- [TwelveMonkeys WebP Maven metadata](https://repo.maven.apache.org/maven2/com/twelvemonkeys/imageio/imageio-webp/maven-metadata.xml)
- [openhtmltopdf 릴리스](https://github.com/openhtmltopdf/openhtmltopdf/releases)
- [jsoup 1.23.2 릴리스](https://jsoup.org/news/release-1.23.2)
- [Lombok 변경 이력](https://projectlombok.org/changelog)
- [Bootstrap npm latest](https://registry.npmjs.org/bootstrap/latest)

## 검증

```powershell
# 전체 호환성 테스트와 패키징
mvn verify

# PDF 자원 처리 회귀 테스트만 실행
mvn "-Dtest=PdfConverterTest" test

# 목차·제목 번호·인라인 서식 회귀 테스트
mvn "-Dtest=MarkdownServiceTest" test

# DOCX 의미 구조와 왕복 변환 회귀 테스트
mvn "-Dtest=DocxStructureTest" test

# DOCX 역변환의 내용·서식 보존 회귀 테스트
mvn "-Dtest=DocxToMarkdownConverterTest" test

# ZIP 이미지 경로와 API 호환성 테스트
mvn "-Dtest=ImageResolverTest,DependencyCompatibilityTest" test

# 이미지 형식·한도·경고와 실제 PDF/DOCX 이미지 검증
mvn "-Dtest=ImageResolverFormatsTest,ImageConversionTest" test

# 실제 해석된 의존성 버전 확인
mvn dependency:list
```

2026-10-27 기준 **330개 테스트 통과**: 호환성 82개, DOCX 구조 27개, DOCX 역변환 42개, Markdown 32개, PDF 회귀 49개, 설정 9개, 이미지 경로 16개, 이미지 형식·한도 53개, 이미지 출력 20개.

- Spring Boot 4 애플리케이션 컨텍스트, MVC·Thymeleaf·JSON 응답
- H1 유무·목차/번호 옵션 조합, Setext·단계 생략·중복 ID, 인라인 서식·엔티티·코드 블록 보존
- PDF/DOCX 변환 API의 제목·목차 표시와 내부 대상 대응, DOCX 제목 북마크·링크, 미리보기 번호 옵션
- 실제 PDF 생성 및 위치 정렬한 텍스트 추출
- 동일 PNG의 로컬·HTTP(S)·Base64 입력에 대한 PDF 이미지 객체·픽셀 검증
- PNG/JPEG/GIF/BMP/SVG/WebP의 로컬·원격·Base64 교차 판별, 실제 DOCX 미디어 타입과 PDF 픽셀·비율·본문/셀 폭 검증
- 50MiB·600만 픽셀 경계, 잘못된 확장자·MIME·Base64, SVG 외부 접근 차단, 실패별 단일 경고와 API 경고 조회
- PDF 목차의 실제 대상 페이지·위치, 링크 전략·앵커 ID 보존, URI·HTML 엔티티, 누락·미지원 주소 경고
- ZIP → PDF의 이미지·목차·첨부 경고 및 기존 경로·원격 주소 접근 제한 유지
- 루트·중첩 ZIP의 PDF/DOCX 이미지 바이트·픽셀, 문서 상대·루트·상위 경로, 인코딩된 파일명과 경계 차단
- 이미지 없음·누락·잘못된 경로·변환 오류·MD 없음의 임시 디렉터리 정리
- DOCX 생성·다운로드, 표·하이퍼링크·ZIP 이미지 임베드
- DOCX H1~H6 스타일·outline·북마크·인라인 서식과 제목 크기, 최대 9단계 혼합 목록, 시작 번호·재시작·중첩·문단 경계의 왕복 변환
- 생성 DOCX의 H2 기반 챕터 분리, Word 번호 시작값 override, 번호 정의 누락·Markdown 범위 밖 시작값 경고
- DOCX → Markdown, 이미지 추출 ZIP, 챕터 분리
- 혼합 Run의 텍스트·반복 이미지·줄바꿈·탭 순서, 특수문자·공백·인접 서식·코드·링크 주소·표 셀의 재파싱 결과
- 혼합 목록과 일반 문단 경계, 이미지/챕터 ZIP의 실제 바이트·내용, 챕터 표지 링크, 미지원 구조의 변환 응답·경고 조회
- Jackson 3 설정 저장·로드·초기화·기존 JSON 속성 호환
- 잘못된 입력과 설정 읽기·쓰기 실패의 오류 응답

테스트는 실제 사용자 설정 대신 메모리 설정과 임시 파일을 사용합니다. 원격 이미지 성공 경로는 외부 네트워크 없이 응답을 대체해 검증합니다. 실제 원격 서버 다운로드, Word/LibreOffice의 표시, 한글 글꼴 조합, 부하 성능까지 검증한 것은 아닙니다.

## 현재 제한 사항

다음 개선 과제가 남아 있습니다.

- DOCX의 미지원 편집 구조·병합 레이아웃·페이지 배치까지 역변환하지는 않습니다. Markdown → DOCX의 링크 내부 이미지·로컬 링크 보존도 개선이 필요합니다.
- 설정의 형식별 반영과 API 옵션 처리에 차이가 있습니다.
- 미리보기는 최종 출력과 같지 않으며 응답 순서 역전 및 오류 표시 개선이 필요합니다.
- 결과 만료·용량·동시성 정책과 실제 글꼴 기반 경고는 추가 개선이 필요합니다.

원인·수정 방향·완료 기준은 [PLAN.md](PLAN.md)에 정리되어 있습니다.

## 라이선스

프로젝트 라이선스는 [LICENSE](LICENSE), 사용 라이브러리 고지는 [NOTICE](NOTICE)를 확인하세요.
