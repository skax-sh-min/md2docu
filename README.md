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

### HTML 미리보기와 재시도

- 입력 변경은 600ms 동안 모아 갱신합니다. **미리보기 갱신** 버튼과 소제목 번호 변경은 즉시 갱신하며, 대기 중인 자동 갱신을 취소해 같은 입력을 중복 요청하지 않습니다.
- 입력이 바뀐 순간부터 이전 요청을 무효화합니다. `AbortController`로 취소를 시도하고 요청 순번도 검사하므로, 취소를 무시한 응답이나 늦은 JSON 본문·오류가 최신 결과와 상태를 덮어쓰지 않습니다. 클라이언트 취소가 서버의 처리 중단까지 보장하지는 않습니다.
- 갱신 대기·처리 중·실패 상태를 별도로 표시합니다. **마지막 정상 결과는 유지하되 현재 입력과 다를 수 있는 이전 결과임을 표시**합니다. 처음부터 성공 결과가 없다면 내용 없는 화면에 오류 상태를 안내합니다.
- 실패하면 **미리보기 다시 시도** 버튼으로 현재 입력·번호 옵션을 다시 요청할 수 있습니다. 정상 응답은 `html`이 문자열인 객체여야 하며, 잘못된 구조·JSON·HTTP·통신 오류를 성공 결과로 처리하지 않습니다. 빈 입력과 빈 HTML 문자열은 정상입니다.
- 빠른 미리보기에는 **입력 텍스트와 소제목 번호만** 반영합니다. 목차·이미지 포함·링크 처리·페이지 크기·출력 설정은 최종 PDF/DOCX에만 적용됩니다. 로컬 이미지는 미리보기에서 보이지 않을 수 있으며 최종 페이지 배치를 재현하지 않습니다.

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

업로드는 `.md`·`.zip`만 받으며 확장자는 대소문자를 구분하지 않습니다. Markdown 파일은 UTF-8이어야 하고 BOM은 허용합니다. 빈 파일·공백뿐인 Markdown·손상된 ZIP은 거부합니다. 다운로드명에는 클라이언트 폴더 경로를 제외한 원본 이름의 대소문자와 한글을 보존하며, 제어문자와 파일명으로 사용할 수 없는 문자는 제거합니다.

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
- URL 경로가 `.md` 또는 `.zip`으로 끝나야 하며, 확장자는 다운로드 전에 확인합니다. 다운로드 최대 크기는 10MiB(10,485,760바이트)입니다.
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
| GET | `/api/system/info` | 실제 PDF 글꼴 검사 결과·한글 샘플 지원·진단 |
| GET / PUT | `/api/settings` | 출력 설정 조회·저장 |
| GET | `/api/settings/defaults` | 서버 기본 출력 설정 조회, 저장하지 않음 |
| POST | `/api/settings/reset` | 기본 출력 설정으로 저장·복원 |
| GET | `/api/settings/fonts` | 실제 PDF 로딩 검사를 통과한 글꼴 패밀리 |

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
  "warnings": [],
  "expiresAt": "2026-10-27T08:00:00Z"
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
| `pageSize` | `A4` | `A4`, `LETTER`. 대소문자와 앞뒤 공백 정규화 |
| `includeImages` | `true` | 이미지 임베드 여부 |
| `linkStrategy` | `keep` | `keep`, `warn`, `ignore`. 대소문자와 앞뒤 공백 정규화 |
| `remoteImageTimeout` | `5000` | 이미지 요청 제한(ms), 텍스트·파일·URL 공통. 1000~30000ms 범위 밖이면 가장 가까운 경계값으로 보정 |
| `generateToc` | `false` | 목차 생성 |
| `numberHeadings` | `false` | H2~H6 소제목 번호 생성 |

세 입력 방식은 같은 옵션 계약을 사용합니다. `{format}`의 `PDF`·`Docx` 같은 대소문자 변형도 허용합니다. JSON은 문자열·boolean·32비트 정수 타입을 구분하며 명시적인 `null`이나 `"true"`·`"5000"` 같은 문자열 대체값을 거부합니다. multipart의 boolean은 `true`/`false`(대소문자 무관), 타임아웃은 정수 문자열로 전달하세요. 옵션을 생략하면 위 기본값을 사용합니다.

변환할 Markdown은 공백이 아닌 내용을 포함해야 합니다. 미리보기는 `markdown`, boolean `numberHeadings`를 받으며 빈 입력은 허용합니다. 미리보기는 최종 페이지 레이아웃을 재현하지 않습니다.

| 링크 전략 | 현재 동작 및 제한 |
|-----------|------------------|
| `keep` | PDF/DOCX 모두 로컬 참조 대상·외부 링크·내부 앵커를 보존 |
| `warn` | 로컬 링크 기능을 제거하고 첨부 미포함 표시와 `ATTACHMENT_NOT_FOUND` 경고를 반환. 외부 링크·내부 앵커는 유지 |
| `ignore` | 링크 기능만 제거하고 내부 콘텐츠는 보존. 링크 주소 검증·링크 경고는 생략 |

모든 전략에서 링크 내부 텍스트·강조·코드·줄바꿈·이미지의 순서를 보존합니다. **이미지 포함**을 끄면 링크 안의 이미지도 제외하며, 이미지 로드 실패는 일반 이미지와 같은 경고·플레이스홀더로 알립니다. DOCX의 제목·목록·인용문·표 안에서도 같은 규칙을 적용합니다.

로컬 링크를 유지하는 것과 파일 자체를 결과물에 첨부하는 것은 다릅니다. DOCX는 원래 URI 참조를 하이퍼링크 관계에 기록할 뿐 파일을 읽거나 포함하지 않습니다. 첨부파일 자동 포함 기능은 없으며, 상대 링크의 실제 열기 성공 여부는 결과 문서의 저장 위치·대상 파일·뷰어에 달려 있습니다.

PDF/DOCX는 같은 링크 주소 정책을 사용합니다. HTTP(S)·mailto·FTP와 로컬 상대/루트 경로·내부 프래그먼트를 허용합니다. `file:`·`jar:`·실행 스킴·data 링크, 잘못된 URI 및 `//host/path`처럼 프로토콜을 생략한 외부 주소는 `keep`/`warn`에서 링크를 제거하고 `UNSUPPORTED_RESOURCE` 경고를 반환합니다. 링크 내부 콘텐츠는 유지합니다.

DOCX는 HTML의 `id`와 `<a name="...">`를 실제 Word 북마크에 연결합니다. 이름은 영문자로 시작하는 최대 40자의 영문·숫자·밑줄 형식으로 매핑하며 대소문자만 다른 이름도 충돌하지 않게 처리합니다. 한글·공백·긴 ID와 생성 목차의 대상도 같은 매핑을 사용하고, 중복된 원본 ID는 첫 대상을 가리킵니다. URI 프래그먼트를 디코딩해 대상을 찾으며, 대상이 없거나 실제 본문에 출력되지 않으면 **콘텐츠는 보존하고 링크만 제거**합니다. 이때 원래 주소를 대상으로 `HYPERLINK_UNRESOLVED`를 반환합니다. 이 누락 대상 검사는 DOCX 동작이며 PDF에 같은 검사를 추가한 것은 아닙니다.

Word/LibreOffice에서 링크 내부 이미지 클릭과 외부·로컬·북마크 이동은 별도 수동 확인이 남아 있습니다. OOXML 구조·관계·이미지 바이트 검증이 실제 뷰어의 동작 확인을 대신하지는 않습니다. DOCX → Markdown에서는 북마크 대상을 재생성하지 않으므로 내부 링크의 완전한 왕복 이동도 보장하지 않습니다.

### 오류 응답 계약

오류는 HTTP 상태와 함께 `{"error":"사용자 안내"}` JSON으로 반환합니다. 정상 변환 응답의 `jobId`, `downloadUrl`, `fileName`, `warnings`는 그대로 유지하며, 모든 변환 방식에 UTC ISO-8601 형식의 `expiresAt`을 추가합니다. 이는 보관 기한이며 용량 퇴출·서버 재시작까지 막는 보장은 아닙니다.

| HTTP | 의미 |
|------|------|
| 400 | 잘못된 옵션·타입·JSON·URL, 빈 Markdown/파일, 잘못된 UTF-8, 손상된 ZIP/DOCX, 본문 영역·DOCX 목록 깊이 제한 위반 |
| 404 | 없거나 만료·퇴출된 변환 작업의 다운로드·경고 조회 |
| 413 | 업로드·요청·ZIP 압축 해제·URL 다운로드 크기 초과, 단일 변환 결과가 결과 보관 총 용량 상한 초과 |
| 415 | 지원하지 않는 변환 형식·파일 확장자·Content-Type |
| 500 | 서버 내부 I/O·예상하지 못한 처리 실패 |
| 502 | 원격 호스트 조회·통신·HTTP 오류 또는 따라가지 않는 리다이렉트 |
| 504 | 원격 URL 요청 타임아웃 |

경고가 없는 정상 작업은 경고 조회에서 `200 + []`를 반환합니다. DOCX 역변환은 0바이트가 아닌 `.docx` 파일만 받으며, 유효한 빈 Word 문서 자체는 허용합니다. UI에서도 서버 오류를 표시하고, 지원하지 않거나 비어 있는 파일을 선택하면 이전 선택을 해제합니다.

## 출력 설정

상단 **출력 설정**에서 글꼴, 본문·코드·H1~H6 크기, 줄 간격, 상하좌우 여백을 저장합니다.

- 저장 위치: Java `user.home` 아래 `.md2docu/settings.json`. Windows에서는 일반적으로 `%USERPROFILE%\.md2docu\settings.json`입니다.
- 서버 계정의 설정 하나를 공유하므로 여러 사용자가 접속하면 같은 설정을 사용합니다.
- PDF/DOCX는 변환 시작 시 설정을 복사해 사용합니다. 변환 도중 설정을 저장해도 진행 중인 문서 안에서는 기존 설정이 유지됩니다.
- 본문·제목·목록과 Normal 스타일을 상속하는 DOCX 문단에 설정한 줄 간격을 적용하고, PDF/DOCX 모두 H4·H5·H6 크기를 개별 반영합니다. PDF의 추가 HTML 기본 본문 여백도 제거해 페이지 여백 설정과 출력 위치를 맞춥니다.
- 모달의 **기본값으로**는 `/api/settings/defaults`에서 받은 서버 기본값으로 입력 폼만 변경합니다. **저장**을 눌러야 반영됩니다.
- `POST /api/settings/reset`은 기본값을 즉시 저장합니다.
- 저장 성공 응답의 정규화된 값을 폼에 반영하며, 설정·글꼴 조회나 저장 실패는 모달에 표시합니다. 늦게 도착한 이전 요청이 다시 연 모달의 값을 덮어쓰지 않도록 처리합니다.
- 저장은 같은 폴더의 임시 파일 기록·동기화 후 **원자적 교체**로 수행합니다. 실패하면 기존 파일과 메모리 설정을 유지합니다. 원자적 교체를 지원하지 않는 파일시스템에서는 비원자적 저장으로 대체하지 않고 HTTP 500을 반환합니다.
- 설정 파일이 없으면 기본값을 사용합니다. 손상·읽기 실패는 기본값으로 조용히 대체하지 않고 HTTP 500 오류와 서버 로그로 알립니다.
- 손상된 파일은 먼저 백업한 뒤 수정하거나 설정 초기화 API로 복원하세요.

### 페이지 여백과 기존 설정

| 항목 | 정책 |
|------|------|
| 기본 여백 | 좌·우 각각 **15mm**, 상·하 각각 **14mm** |
| 최소 본문 영역 | 페이지에서 여백을 뺀 너비 **180mm**, 높이 **250mm** 이상 |
| 설정 저장 | A4와 LETTER 모두 위 기준을 만족해야 함 |
| 문서 변환 | 선택한 페이지 크기의 본문 영역을 변환 시작 시 검증 |

여백은 기존과 같이 각 5~100mm 범위로 보정한 뒤 조합을 검증합니다. 정수 여백을 두 용지에 모두 적용하려면 좌우 합계는 최대 30mm, 상하 합계는 최대 29mm여야 합니다. 기준 미달 시 저장·변환 API는 안내 문구를 담은 HTTP 400 `{"error":"..."}`를 반환합니다.

기존 파일의 여백은 **자동 변경하지 않습니다**. 예전 기본값인 좌우 25mm·상하 20mm는 조회할 수 있지만 저장·변환 시 거부됩니다. 출력 설정을 열어 여백을 줄이거나 **기본값으로 → 저장**을 실행하세요.

## 크기 제한과 결과 보관

| 항목 | 기본값 | 설정 키 또는 정책 |
|------|--------|-------------------|
| 업로드 파일 최대 크기 | 200MB | `spring.servlet.multipart.max-file-size` |
| multipart 요청 최대 크기 | 210MB | `spring.servlet.multipart.max-request-size` |
| ZIP 압축 해제 최대 크기 | 350MB | `app.zip.max-extract-bytes` |
| URL 다운로드 최대 크기 | 10MiB (10,485,760바이트) | 현재 코드 상수 |
| 이미지 한 개의 바이트 크기 | 50MiB (52,428,800바이트) | 로컬·HTTP(S)·Base64 공통 상수 |
| 이미지 한 개의 픽셀 수 | 6,000,000픽셀 | 너비 × 높이, SVG 출력에도 적용 |
| 결과 보관 TTL | 3600초 | `app.convert.temp-expiry-seconds` |
| 결과 보관 개수 | 최대 500개 | `app.convert.max-stored-results` |
| 결과 파일 총 보관 용량 | 256MiB (268,435,456바이트) | `app.convert.max-stored-bytes` |
| 만료 결과 정리 간격 | 60,000ms (1분) | `app.convert.cleanup-interval-ms` |

위 결과 보관 값은 [application.properties](src/main/resources/application.properties)에서 변경할 수 있고, 실행 인자로도 재정의할 수 있습니다. TTL·개수·총 바이트 한도는 양수여야 하며 0·음수·잘못된 타입은 서버 시작 시 거부합니다. 변경 값은 서버 시작 시 읽으며 런타임 설정 UI는 제공하지 않습니다.

```powershell
java -jar .\target\md2docu-0.1.4-SNAPSHOT.jar `
    --spring.servlet.multipart.max-file-size=200MB `
    --spring.servlet.multipart.max-request-size=210MB `
    --app.zip.max-extract-bytes=367001600 `
    --app.convert.temp-expiry-seconds=3600 `
    --app.convert.max-stored-results=500 `
    --app.convert.max-stored-bytes=268435456 `
    --app.convert.cleanup-interval-ms=60000
```

결과는 **메모리에 임시 보관하며 서버 재시작 시 사라집니다**. 결과 저장용 디스크 파일이나 재시작 복구는 이번 범위에 포함하지 않습니다.

- TTL은 변환 결과를 저장할 때부터 계산하며 다운로드·경고 조회로 연장하지 않습니다. `expiresAt` 직전까지 조회할 수 있고, **경계 시각부터 두 조회 모두 JSON 404**를 반환합니다. 정리 스케줄 실행 여부와 무관하게 조회 시 검사합니다.
- 저장·조회·만료 정리·퇴출은 같은 잠금으로 보호합니다. 저장 전 만료 결과를 정리하고, 개수 또는 총 바이트 한도를 지킬 때까지 가장 오래 **저장한** 결과부터 제거합니다. 조회 순서로 퇴출 순서를 변경하지 않습니다.
- 한도에 정확히 맞는 결과는 허용합니다. 단일 결과가 총 바이트 상한보다 크면 HTTP 413으로 거부하며, 이 요청 때문에 기존 결과를 퇴출하지 않습니다.
- 다운로드가 만료·퇴출 전에 결과 스냅샷을 확보했다면 이미 시작한 전송은 완료할 수 있습니다. 이후 새 다운로드·경고 조회는 404입니다. 두 조회의 정상 응답에는 `Cache-Control: no-store`를 지정합니다.
- UI는 보관 기한과 한도·재시작에 따른 조기 삭제 가능성을 안내합니다. 자동 다운로드·재다운로드가 가능하더라도 필요한 결과는 바로 저장하세요.
- 총 용량은 **저장소에 보관 중인 결과 파일 바이트의 합계**입니다. 메타데이터·경고, 변환 중 데이터, 스냅샷 복사·진행 중 다운로드의 메모리는 포함하지 않습니다. 따라서 전체 JVM 메모리나 동시 변환·다운로드 수의 상한이 아니며 메모리 부족 복구를 보장하지 않습니다. 디스크 결과 저장을 도입하지 않았으므로 별도의 결과 파일 쓰기 실패·고아 파일 정리는 없습니다.

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

### PDF 글꼴 검사와 한글 안내

[application.properties](src/main/resources/application.properties)의 `app.pdf.font.paths`에 `경로|패밀리명`을 쉼표로 구분해 지정합니다. **서버 시작 시 실제 PDFBox 로딩·임베딩 준비와 기본 한글 샘플 인코딩을 검사**하며, 파일 존재나 OS 이름만으로 지원 여부를 추정하지 않습니다. 기본 후보 경로가 있어도 해당 서버 계정이 읽을 수 있는 실제 파일을 설정해야 합니다.

- TrueType 윤곽선의 TTF·TTC를 지원합니다. TTC는 각 글꼴 면을 따로 검사·등록하고, 메타데이터의 굵기와 기울임을 사용합니다. 같은 패밀리의 보통·굵은 TTF도 각각의 weight로 등록합니다.
- CFF 윤곽선, 손상된 파일, 임베딩할 수 없는 글꼴 면은 제외하고 진단합니다. `.ttc`라는 확장자나 Noto라는 이름만으로 지원한다고 판단하지 않습니다. 일부 Noto Sans CJK 배포본은 CFF 컬렉션이므로 실제 검사 결과를 확인하고, 필요하면 사용 권한이 있는 TrueType 윤곽선 한글 글꼴을 지정하세요.
- 기본 한글 샘플은 `가나다라마바사아자차카타파하한글힣`입니다. 샘플 통과는 해당 설정 후보의 제한된 검사 결과이며 **모든 한글·다른 문자·선택한 CSS 글꼴·최종 레이아웃의 표시 보장이 아닙니다**.
- 본문 설정의 패밀리 목록에 등록한 별칭을 포함하세요. 코드 블록은 기존 Courier 계열 뒤에 본문 글꼴을 대체 글꼴로 연결해 한글을 표시합니다.
- 글꼴 파일과 설정을 바꾼 뒤에는 서버를 재시작하세요. 시작 시 검사 결과와 변환 중 실제 로딩은 구분하며, 이후 파일 삭제·등록 실패는 변환 경고로 알립니다.

```powershell
# Windows: 설치된 맑은 고딕의 보통·굵은 글꼴을 같은 패밀리로 등록
java -jar .\target\md2docu-0.1.4-SNAPSHOT.jar `
    "--app.pdf.font.paths=C:\Windows\Fonts\malgun.ttf|Malgun Gothic,C:\Windows\Fonts\malgunbd.ttf|Malgun Gothic"
```

Linux는 사용 권한이 있는 TrueType 한글 글꼴을 시스템 패키지나 배포 과정에서 설치하고 실제 경로를 지정합니다. 예를 들어 NanumGothic TTF를 설치했다면 다음과 같이 기본 본문 패밀리 목록에 있는 별칭으로 등록할 수 있습니다. 경로는 배포판마다 다르며 이 예제는 설치 명령이나 파일 존재를 보장하지 않습니다.

```sh
java -jar target/md2docu-0.1.4-SNAPSHOT.jar \
  "--app.pdf.font.paths=/usr/share/fonts/truetype/nanum/NanumGothic.ttf|Noto Sans CJK,/usr/share/fonts/truetype/nanum/NanumGothicBold.ttf|Noto Sans CJK"
```

macOS도 설치된 한글 TTF·TrueType TTC의 실제 경로를 지정하고 서버 실행 계정의 읽기 권한을 확인하세요. 개발자 개인 홈 경로나 OS 기본 글꼴 이름만 믿고 배포하지 마세요. **글꼴 파일은 프로젝트에 포함하지 않으며 자동 설치·다운로드하지 않습니다.** 사용·임베딩 권한은 배포자가 확인해야 합니다.

`GET /api/system/info`는 기존 boolean `pdfKoreanWarning`을 유지하며 다음 정보를 추가합니다. 경고 여부는 기본 한글 샘플을 지원하는 사용 가능한 면이 있는지로 계산합니다. 서버의 절대 파일 경로는 응답에 포함하지 않습니다.

| 필드 | 의미 |
|------|------|
| `pdfFonts.checkedAt` | 시작 시 검사 시각 |
| `pdfFonts.sample` | 검사한 한글 샘플 |
| `pdfFonts.fonts` | 로딩 가능한 면의 `family`, `name`, `weight`, `style`, `koreanSampleSupported` |
| `pdfFonts.issues` | 패밀리별 `NOT_FOUND`, `INVALID_CONFIGURATION`, `LOAD_FAILED` 진단 |
| `verificationScope` | `configured-fonts-korean-sample`: 설정 후보·샘플 한정 검사 |

PDF 선택 시 상태 확인 중·조회 실패·한글 글꼴 없음·샘플 확인 결과를 구분합니다. 환경 정보가 늦게 도착해도 현재 출력 형식을 다시 확인하며 DOCX에서는 숨깁니다. 샘플 확인 상태도 보장 문구 대신 검사 범위의 한계를 안내합니다.

| 변환 경고 | 의미 |
|-----------|------|
| `PDF_FONT_UNAVAILABLE` | 잘못된/손상된 설정 글꼴 또는 변환 중 실제 로딩·등록 실패. 대상은 패밀리명 |
| `PDF_KOREAN_FONT_UNAVAILABLE` | 원본 본문에 한글이 있지만 기본 샘플을 지원하는 글꼴이 등록되지 않음 |

합의한 정책대로 글꼴 경고가 있어도 PDF 변환은 계속합니다. 결과의 한글이 누락될 수 있으므로 경고를 확인하고 글꼴을 설정하거나 DOCX를 사용하세요. 이미지 실패 등으로 자동 생성한 한글 안내문 때문에 원본이 영문인 문서에 추가 한글 경고를 만들지는 않습니다.

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

# DOCX 링크 콘텐츠·북마크와 ZIP 업로드/다운로드 회귀 테스트
mvn "-Dtest=DocxLinksTest,DependencyCompatibilityTest" test

# ZIP 이미지 경로와 API 호환성 테스트
mvn "-Dtest=ImageResolverTest,DependencyCompatibilityTest" test

# 이미지 형식·한도·경고와 실제 PDF/DOCX 이미지 검증
mvn "-Dtest=ImageResolverFormatsTest,ImageConversionTest" test

# 출력 설정·원자적 저장·설정 API와 실제 문서의 설정 스냅샷 검증
mvn "-Dtest=UserSettingsServiceTest,SettingsControllerTest,OutputSettingsTest" test

# 세 입력 방식의 API 계약·입력 검증·실제 HTTP 업로드 한도
mvn "-Dtest=ConversionApiTest,ConvertServiceInputTest,MultipartLimitTest" test

# 결과 보관 TTL·용량·동시성·다운로드 수명주기
mvn "-Dtest=ConversionResultStoreTest,ConversionRetentionApiTest" test

# 미리보기 템플릿·API 계약 검증과 패키징
mvn "-Dtest=DependencyCompatibilityTest#rendersThymeleafPageWithMatchingBootstrapAssets+previewsMarkdownThroughJacksonAndSpringMvc,ConversionApiTest" verify

# 실제 미리보기 JS 핸들러의 요청 경쟁·상태·재시도 회귀 테스트
node --test src\test\js\preview.test.cjs

# PDF 글꼴 검사·OS 독립 환경 API·한글 TTF/TTC 출력
mvn "-Dtest=FontServiceTest,PdfFontsTest,PdfEnvironmentApiTest" test

# 미리보기와 PDF 글꼴 환경 정보의 지연 응답·상태 테스트
node --test src\test\js\preview.test.cjs src\test\js\pdf-environment.test.cjs

# 실제 해석된 의존성 버전 확인
mvn dependency:list
```

2026-10-27 B12 완료 기준 **Java 전체 624개 테스트 통과**: 호환성 99개, 변환 API 계약 65개, PDF 환경 API 10개, 글꼴 검사 12개, 한글 PDF 글꼴 출력 7개, 입력 검증 57개, 실제 HTTP 업로드 한도 4개, 결과 보관 31개, 보관/다운로드 API 13개, DOCX 구조 27개, DOCX 링크 52개, DOCX 역변환 42개, Markdown 32개, PDF 회귀 49개, 설정 저장 23개, 설정 API 5개, 출력 설정 7개, 이미지 경로 16개, 이미지 형식·한도 53개, 이미지 출력 20개. 실패·오류·건너뜀 없이 `mvn verify`와 실행 JAR 패키징을 확인했습니다.

JavaScript는 **미리보기 44개와 PDF 환경 안내 22개, 총 66개**가 통과했습니다. [preview.test.cjs](src/test/js/preview.test.cjs)와 [pdf-environment.test.cjs](src/test/js/pdf-environment.test.cjs)는 실제 템플릿 핸들러를 읽어 타이머·DOM·fetch 대역과 지연 응답으로 검사합니다. Node.js 기본 테스트 러너를 사용하므로 별도 npm 패키지가 필요하지 않습니다(Node.js 22 이상 권장). 이 66개는 Maven 테스트 합계에 포함하지 않습니다.

한글 TTF 보통·굵은 면과 TTC 출력 테스트는 설치된 글꼴을 사용하며 파일을 프로젝트에 복사하지 않습니다. 이번 Windows 환경에서는 맑은 고딕·굴림으로 모두 실행했고, 한글 본문·굵기·코드 블록의 추출 텍스트·임베딩·래스터 렌더링을 확인하고 생성 이미지도 시각 확인했습니다. 다른 환경에서는 `test.pdf.korean.regular`, `test.pdf.korean.bold`, `test.pdf.korean.collection` 시스템 속성으로 사용 권한이 있는 설치 글꼴 경로를 지정하세요. 해당 파일이 없으면 이 두 출력 테스트는 명시적으로 건너뜁니다. 선택적으로 `"-Dtest.pdf.render-preview=true"`를 추가하면 확인용 PNG를 빌드 출력에 생성합니다.

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
- DOCX의 `keep`/`warn`/`ignore`별 링크 내부 텍스트·서식·줄바꿈·이미지 순서, 원래 로컬 URI 관계, 이미지 제외·실패·반복 표시와 ZIP API 경고 조회
- Word 호환 북마크 이름·중복/대소문자 충돌·인라인/블록/목록/표/코드 대상·id/name 별칭, 없는/출력되지 않은 대상의 링크 제거와 콘텐츠 보존
- DOCX H1~H6 스타일·outline·북마크·인라인 서식과 제목 크기, 최대 9단계 혼합 목록, 시작 번호·재시작·중첩·문단 경계의 왕복 변환
- 생성 DOCX의 H2 기반 챕터 분리, Word 번호 시작값 override, 번호 정의 누락·Markdown 범위 밖 시작값 경고
- DOCX → Markdown, 이미지 추출 ZIP, 챕터 분리
- 혼합 Run의 텍스트·반복 이미지·줄바꿈·탭 순서, 특수문자·공백·인접 서식·코드·링크 주소·표 셀의 재파싱 결과
- 혼합 목록과 일반 문단 경계, 이미지/챕터 ZIP의 실제 바이트·내용, 챕터 표지 링크, 미지원 구조의 변환 응답·경고 조회
- Jackson 3 설정 저장·로드·초기화·기존 JSON 속성 호환
- 반환된 설정과 캐시의 분리, 부분 직렬화·교체 실패 시 기존 파일 보존, 원자적 교체 미지원 시 재시도 없이 실패
- A4/LETTER 본문 영역 경계, 구형 설정 조회·보존·수정과 설정/변환 API의 HTTP 400
- 실제 PDF 글자 크기·줄 기준선·좌표와 DOCX 스타일·줄 간격·여백, 이미지 처리 도중 설정이 바뀌어도 한 문서의 스냅샷 유지
- 서버 기본값의 읽기 전용 조회와 저장·다시 조회·재로드·즉시 초기화의 일치
- 텍스트·파일·URL 옵션 일치, 고정 로케일 정규화, 타임아웃 기본값·1000/30000ms 경계와 범위 밖 보정, 엄격한 JSON/폼 타입
- 파일·요청 한도를 16바이트·1KiB로 낮춘 실제 HTTP 검사: 파일 16/17바이트와 전체 요청 1024/1025바이트 경계, JSON 413
- URL 다운로드 10MiB/10MiB+1 경계, 원격 HTTP·DNS·통신·타임아웃의 502/504 구분, 손상된 DOCX XML·ZIP과 실패 시 임시 폴더 정리
- 원본 파일명의 대소문자·한글과 다운로드 헤더, 대문자 링크 전략의 실제 PDF/DOCX 링크, 없는 작업과 경고 없는 정상 작업 구분
- 제어 가능한 시계로 TTL 1ns 직전·경계·1ns 직후의 다운로드/경고 조회, 조회 시 기한 불변, 정확한 스케줄 정리·바이트 차감
- 설정 바인딩·잘못된 값의 시작 실패, 정확한 개수/바이트 경계와 초과 거부, 만료 우선·동일 시각 FIFO·다중 퇴출·외부 객체 수정 격리
- 동시 저장·조회·정리 1,440회 쓰기에서 개수/총 바이트 상한과 실제 배열 길이 합계의 일치, HTTP 전송을 막아 둔 상태의 만료·퇴출 후 정상 다운로드 완료
- 변환 응답의 `expiresAt`, 만료·퇴출 후 JSON 404, 단일 결과 초과의 JSON 413와 기존 결과 보존, 다운로드/경고의 캐시 금지
- 실제 PDF 글꼴 로딩·한글 샘플 인코딩, 손상/누락/빈 TTC 진단·경로 비노출, TTF 보통/굵은 면과 TTC 개별 등록·한글 코드 블록
- OS×글꼴 유무/한글 지원 조합의 동일한 환경 API, PDF 선택 전후의 지연 응답·조회 실패·잘못된 응답, 한글 글꼴 미등록 시 다운로드 유지와 응답/조회 경고 일치
- 입력 직후·600ms 경계·수동 갱신·번호 변경의 요청 경쟁, 취소 준수/무시·응답/JSON 본문 역전, 오래된 성공/실패/finally/타이머의 최신 결과·오류·상태 보호
- 잘못된 정상 응답 구조·HTTP 오류 본문·JSON·통신 오류, 빈 입력/HTML, 이전 결과 표시·초기 실패·재시도와 접근성 상태·옵션 범위 안내
- 잘못된 입력과 설정 읽기·쓰기 실패의 오류 응답

테스트는 실제 사용자 설정 대신 메모리 설정과 임시 파일을 사용합니다. 원격 이미지·URL 성공 경로와 실패는 외부 네트워크 없이 응답을 대체해 검증하고, 업로드 한도는 임시 로컬 서버로 확인합니다. 별도로 실제 JavaScript 핸들러를 DOM·fetch 대역에서 실행해 설정 모달 10개, 변환·미리보기·파일 선택·오류 처리 16개, 결과 보관 기한 표시·잘못된 만료 시각 거부 9개 시나리오를 확인했습니다. 위 JavaScript 66개는 프로젝트에 유지하는 별도 Node 자동 테스트이며 실제 브라우저 시각 검증은 아닙니다. 실제 원격 서버 다운로드, Word/LibreOffice의 표시, 모든 한글 글꼴·문자 조합, 부하 성능까지 검증한 것은 아닙니다.

## 현재 제한 사항

다음 개선 과제가 남아 있습니다.

- DOCX의 미지원 편집 구조·병합 레이아웃·페이지 배치와 북마크 대상까지 역변환하지는 않습니다. 생성한 링크·북마크의 실제 Word/LibreOffice 동작은 별도 수동 확인이 필요합니다.
- HTML 미리보기는 최종 페이지 배치·출력 설정을 재현하지 않습니다. 실제 PDF/DOCX 기반 미리보기는 별도 개선 제안입니다.
- 결과는 임시 메모리 보관이며 재시작 복구·디스크 저장·전체 변환/다운로드 메모리와 동시 실행 수 제한은 제공하지 않습니다.
- PDF 글꼴 검사는 설정 후보·기본 한글 샘플 범위입니다. 모든 글리프·사용자가 선택한 글꼴·서체 조합과 최종 표시를 인증하지 않습니다.

원인·수정 방향·완료 기준은 [PLAN.md](PLAN.md)에 정리되어 있습니다.

## 라이선스

프로젝트 라이선스는 [LICENSE](LICENSE), 사용 라이브러리 고지는 [NOTICE](NOTICE)를 확인하세요.
