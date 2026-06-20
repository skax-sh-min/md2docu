package com.md2docu.controller;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertResult;
import com.md2docu.model.ConvertWarning;
import com.md2docu.service.ConvertService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class ConvertController {

    private static final Set<String> VALID_FORMATS = Set.of("pdf", "docx");
    private static final Set<String> VALID_PAGE_SIZES = Set.of("A4", "LETTER");
    private static final Set<String> VALID_LINK_STRATEGIES = Set.of("keep", "warn", "ignore");

    private final ConvertService convertService;

    public ConvertController(ConvertService convertService) {
        this.convertService = convertService;
    }

    private void requireValidFormat(String format) throws IOException {
        if (!VALID_FORMATS.contains(format.toLowerCase())) {
            throw new IOException("지원하지 않는 변환 형식입니다: " + format + " (지원: pdf, docx)");
        }
    }

    private void requireValidPageSize(String pageSize) throws IOException {
        if (!VALID_PAGE_SIZES.contains(pageSize.toUpperCase())) {
            throw new IOException("지원하지 않는 페이지 크기입니다: " + pageSize + " (지원: A4, LETTER)");
        }
    }

    private void requireValidLinkStrategy(String strategy) throws IOException {
        if (!VALID_LINK_STRATEGIES.contains(strategy.toLowerCase())) {
            throw new IOException("지원하지 않는 링크 전략입니다: " + strategy + " (지원: keep, warn, ignore)");
        }
    }

    /**
     * 파일(md 또는 zip) 업로드 → 변환
     * POST /api/convert/pdf  or  /api/convert/docx
     */
    @PostMapping("/convert/{format}")
    public ResponseEntity<Map<String, Object>> convertFile(
            @PathVariable String format,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "A4") String pageSize,
            @RequestParam(defaultValue = "true") boolean includeImages,
            @RequestParam(defaultValue = "keep") String linkStrategy,
            @RequestParam(defaultValue = "5000") int remoteImageTimeout,
            @RequestParam(defaultValue = "false") boolean generateToc,
            @RequestParam(defaultValue = "false") boolean numberHeadings) throws IOException {

        requireValidFormat(format);
        requireValidPageSize(pageSize);
        requireValidLinkStrategy(linkStrategy);
        ConvertOptions options = buildOptions(pageSize, includeImages, linkStrategy, remoteImageTimeout, generateToc, numberHeadings);
        ConvertResult result = convertService.convertFile(file, format, options);

        return ResponseEntity.ok(toResponseMap(result));
    }

    /**
     * 텍스트 직접 입력 → 변환
     * POST /api/convert/{format}/text
     */
    @PostMapping("/convert/{format}/text")
    public ResponseEntity<Map<String, Object>> convertText(
            @PathVariable String format,
            @RequestBody Map<String, Object> body) throws IOException {

        requireValidFormat(format);
        String markdown = getStringOrDefault(body, "markdown", "");
        ConvertOptions options = new ConvertOptions();
        if (body.containsKey("pageSize")) {
            String pageSize = requireString(body, "pageSize");
            requireValidPageSize(pageSize);
            options.setPageSize(pageSize);
        }
        if (body.containsKey("includeImages"))     options.setIncludeImages(requireBoolean(body, "includeImages"));
        if (body.containsKey("linkStrategy")) {
            String linkStrategy = requireString(body, "linkStrategy");
            requireValidLinkStrategy(linkStrategy);
            options.setLinkStrategy(linkStrategy);
        }
        if (body.containsKey("generateToc"))       options.setGenerateToc(requireBoolean(body, "generateToc"));
        if (body.containsKey("numberHeadings"))    options.setNumberHeadings(requireBoolean(body, "numberHeadings"));

        ConvertResult result = convertService.convertText(markdown, format, options);

        return ResponseEntity.ok(toResponseMap(result));
    }

    /**
     * URL의 Markdown/ZIP 파일 다운로드 후 변환
     * POST /api/convert/{format}/url
     */
    @PostMapping("/convert/{format}/url")
    public ResponseEntity<Map<String, Object>> convertUrl(
            @PathVariable String format,
            @RequestBody Map<String, Object> body) throws IOException {

        requireValidFormat(format);
        String url = getStringOrDefault(body, "url", "");
        ConvertOptions options = new ConvertOptions();
        if (body.containsKey("pageSize")) {
            String pageSize = requireString(body, "pageSize");
            requireValidPageSize(pageSize);
            options.setPageSize(pageSize);
        }
        if (body.containsKey("includeImages"))   options.setIncludeImages(requireBoolean(body, "includeImages"));
        if (body.containsKey("linkStrategy")) {
            String linkStrategy = requireString(body, "linkStrategy");
            requireValidLinkStrategy(linkStrategy);
            options.setLinkStrategy(linkStrategy);
        }
        if (body.containsKey("generateToc"))     options.setGenerateToc(requireBoolean(body, "generateToc"));
        if (body.containsKey("numberHeadings"))  options.setNumberHeadings(requireBoolean(body, "numberHeadings"));

        ConvertResult result = convertService.convertUrl(url, format, options);
        return ResponseEntity.ok(toResponseMap(result));
    }

    /**
     * 변환된 파일 다운로드
     * GET /api/download/{jobId}
     */
    @GetMapping("/download/{jobId}")
    public ResponseEntity<byte[]> download(@PathVariable String jobId) {
        ConvertResult result = convertService.getResult(jobId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }

        String encodedName = URLEncoder.encode(result.getFileName(), StandardCharsets.UTF_8)
                                       .replace("+", "%20");

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(result.getContentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
            .body(result.getFileBytes());
    }

    /**
     * 서버 플랫폼 정보
     * GET /api/system/info
     */
    @GetMapping("/system/info")
    public ResponseEntity<Map<String, Object>> systemInfo() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean pdfKoreanWarning = !os.contains("win");
        return ResponseEntity.ok(Map.of("pdfKoreanWarning", pdfKoreanWarning));
    }

    /**
     * DOCX → Markdown 변환
     * POST /api/convert/md
     */
    @PostMapping("/convert/md")
    public ResponseEntity<Map<String, Object>> convertToMarkdown(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "splitByChapter", defaultValue = "false") boolean splitByChapter) throws IOException {
        ConvertResult result = convertService.convertDocxToMd(file, splitByChapter);
        return ResponseEntity.ok(toResponseMap(result));
    }

    /**
     * Markdown HTML 미리보기
     * POST /api/preview
     */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, String>> preview(@RequestBody Map<String, Object> body) throws IOException {
        String markdown = getStringOrDefault(body, "markdown", "");
        boolean numberHeadings = Boolean.TRUE.equals(body.get("numberHeadings"));
        String html = convertService.preview(markdown, numberHeadings);
        return ResponseEntity.ok(Map.of("html", html));
    }

    /**
     * 변환 경고 목록 조회
     * GET /api/convert/{jobId}/warnings
     */
    @GetMapping("/convert/{jobId}/warnings")
    public ResponseEntity<List<ConvertWarning>> warnings(@PathVariable String jobId) {
        List<ConvertWarning> warnings = convertService.getWarnings(jobId);
        if (warnings == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(warnings);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> handleIoException(IOException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private String requireString(Map<String, Object> body, String key) throws IOException {
        Object val = body.get(key);
        if (!(val instanceof String)) {
            throw new IOException("'" + key + "' 필드는 문자열 타입이어야 합니다.");
        }
        return (String) val;
    }

    private String getStringOrDefault(Map<String, Object> body, String key, String defaultValue) throws IOException {
        if (!body.containsKey(key)) return defaultValue;
        return requireString(body, key);
    }

    private boolean requireBoolean(Map<String, Object> body, String key) throws IOException {
        Object val = body.get(key);
        if (!(val instanceof Boolean)) {
            throw new IOException("'" + key + "' 필드는 boolean 타입이어야 합니다.");
        }
        return (Boolean) val;
    }

    private Map<String, Object> toResponseMap(ConvertResult r) {
        return Map.of(
            "jobId",       r.getJobId(),
            "downloadUrl", r.getDownloadUrl(),
            "fileName",    r.getFileName(),
            "warnings",    r.getWarnings()
        );
    }

    private ConvertOptions buildOptions(String pageSize, boolean includeImages, String linkStrategy,
                                        int remoteImageTimeout, boolean generateToc, boolean numberHeadings) {
        ConvertOptions opts = new ConvertOptions();
        opts.setPageSize(pageSize);
        opts.setIncludeImages(includeImages);
        opts.setLinkStrategy(linkStrategy);
        opts.setRemoteImageTimeout(Math.max(1_000, Math.min(30_000, remoteImageTimeout)));
        opts.setGenerateToc(generateToc);
        opts.setNumberHeadings(numberHeadings);
        return opts;
    }
}
