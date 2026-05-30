# md2docu

Markdown 파일을 **PDF** 또는 **DOCX**로 변환하는 Spring Boot 웹 애플리케이션입니다.

## 주요 기능

- Markdown → PDF / DOCX 변환
- 이미지 자동 임베드 (로컬 파일, 원격 URL, Base64)
- ZIP으로 Markdown + 이미지를 함께 업로드
- 링크 처리 전략 선택 (유지 / 첨부 경고 / 텍스트만)
- 목차 자동 생성 (1. / 1.1. / 1.1.1. 번호 형식, 클릭 가능한 링크 포함)
- 변환 완료 시 파일 자동 다운로드
- 변환 경고 리포트 (이미지 로드 실패, 첨부파일 누락 등)
- 실시간 Markdown 미리보기

## 요구사항

| 항목 | 버전 |
|------|------|
| Java | 17 이상 |
| Maven | 3.8 이상 |

## 설치 및 실행

### 개발 모드 (소스에서 직접 실행)

```bash
git clone https://github.com/your-repo/md2docu.git
cd md2docu
mvn spring-boot:run
```

### JAR 빌드 및 실행

```bash
# 빌드 (target/md2docu-0.1.1-SNAPSHOT.jar 생성)
mvn package -DskipTests

# 실행
java -jar target/md2docu-0.1.1-SNAPSHOT.jar
```

포트를 변경하려면:

```bash
java -jar target/md2docu-0.1.1-SNAPSHOT.jar --server.port=9090
```

서버가 시작되면 http://localhost:8080 에서 웹 UI를 이용할 수 있습니다.

## 사용법

### 웹 UI

1. http://localhost:8080 접속
2. **텍스트 입력** 탭에서 Markdown을 직접 작성하거나,  
   **파일 업로드** 탭에서 `.md` 또는 `.zip` 파일을 업로드
3. 변환 옵션 설정 (형식, 페이지 크기, 이미지 포함 여부, 링크 처리 방식, 목차 생성)
4. **변환하기** 클릭 → 변환된 파일이 자동으로 다운로드됨

> ZIP 업로드 시: Markdown 파일과 이미지를 함께 압축하여 업로드합니다.  
> 내부 상대 경로 구조가 그대로 유지됩니다.
> ```
> archive.zip
> ├── document.md
> └── images/
>     └── figure.png
> ```

### REST API

#### 텍스트 변환

```bash
# PDF 변환
curl -X POST http://localhost:8080/api/convert/pdf/text \
  -H "Content-Type: application/json" \
  -d '{
    "markdown": "# 제목\n\n내용입니다.",
    "pageSize": "A4",
    "includeImages": true,
    "linkStrategy": "keep",
    "generateToc": false
  }'

# 응답
{
  "jobId": "31bfcf55-...",
  "downloadUrl": "/api/download/31bfcf55-...",
  "fileName": "document.pdf",
  "warnings": []
}
```

#### 파일 업로드 변환

```bash
# .md 파일 → DOCX 변환
curl -X POST http://localhost:8080/api/convert/docx \
  -F "file=@document.md" \
  -F "pageSize=A4" \
  -F "linkStrategy=warn"

# .zip 파일 업로드 (이미지 포함)
curl -X POST http://localhost:8080/api/convert/pdf \
  -F "file=@archive.zip"
```

#### 파일 다운로드

```bash
curl -o output.pdf http://localhost:8080/api/download/{jobId}
```

#### 미리보기

```bash
curl -X POST http://localhost:8080/api/preview \
  -H "Content-Type: application/json" \
  -d '{"markdown": "# Hello\n\n**world**"}'
```

#### 변환 경고 조회

```bash
curl http://localhost:8080/api/convert/{jobId}/warnings
```

#### 서버 환경 정보

```bash
curl http://localhost:8080/api/system/info
# {"pdfKoreanWarning": true}
```

### 변환 옵션

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `pageSize` | `A4` | 페이지 크기 (`A4`, `LETTER`) |
| `includeImages` | `true` | 이미지 임베드 여부 |
| `linkStrategy` | `keep` | 링크 처리 방식 (아래 참조) |
| `remoteImageTimeout` | `5000` | 원격 이미지 다운로드 타임아웃 (ms) |
| `generateToc` | `false` | 목차 자동 생성 |

#### linkStrategy 옵션

| 값 | 동작 |
|----|------|
| `keep` | 외부 URL은 클릭 가능한 하이퍼링크로, 로컬 파일 링크는 그대로 유지 |
| `warn` | 로컬 파일 링크에 `[⚠ 첨부파일 미포함]` 경고 표시 + warnings 반환 |
| `ignore` | 링크 제거, 텍스트만 출력 |

## 이미지 처리

| 이미지 유형 | 처리 방식 |
|-------------|-----------|
| 로컬 상대 경로 `./images/fig.png` | ZIP 업로드 시 경로 기준으로 읽어 임베드 |
| 로컬 절대 경로 `/images/fig.png` | ZIP 내부 루트 기준으로 해석하여 임베드 |
| 원격 URL `https://...` | HTTP 요청으로 다운로드 후 임베드 |
| Base64 인라인 `data:image/...` | 그대로 디코딩하여 임베드 |

이미지 로드에 실패하면 변환을 중단하지 않고 플레이스홀더를 삽입한 뒤 `warnings`에 기록합니다.

## 기술 스택

| 역할 | 라이브러리 |
|------|-----------|
| Markdown 파싱 | [flexmark-java](https://github.com/vsch/flexmark-java) 0.64.8 |
| PDF 생성 | [openhtmltopdf](https://github.com/danfickle/openhtmltopdf) 1.0.10 |
| DOCX 생성 | [Apache POI](https://poi.apache.org/) 5.5.1 |
| HTML 파싱 | [Jsoup](https://jsoup.org/) 1.22.2 |
| 웹 프레임워크 | Spring Boot 3.5.14 |
| UI | Bootstrap 5 |

## 한글 폰트 (PDF)

Windows 환경에서는 시스템에 설치된 **맑은 고딕** (`malgun.ttf`)을 자동으로 사용합니다.  
Linux 환경에서는 **Noto Sans CJK** (`NotoSansCJK-Regular.ttc`)를 사용합니다.

폰트가 없는 환경(macOS 등)에서는 한글이 깨질 수 있습니다. 한글 문서는 DOCX 변환을 권장합니다.
