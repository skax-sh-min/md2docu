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

@RestController
@RequestMapping("/api")
public class ConvertController {

    private final ConvertService convertService;

    public ConvertController(ConvertService convertService) {
        this.convertService = convertService;
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
            @RequestParam(defaultValue = "false") boolean generateToc) throws IOException {

        ConvertOptions options = buildOptions(pageSize, includeImages, linkStrategy, remoteImageTimeout, generateToc);
        ConvertResult result = convertService.convertFile(file, format, options);

        return ResponseEntity.ok(Map.of(
            "jobId", result.getJobId(),
            "downloadUrl", result.getDownloadUrl(),
            "fileName", result.getFileName(),
            "warnings", result.getWarnings()
        ));
    }

    /**
     * 텍스트 직접 입력 → 변환
     * POST /api/convert/{format}/text
     */
    @PostMapping("/convert/{format}/text")
    public ResponseEntity<Map<String, Object>> convertText(
            @PathVariable String format,
            @RequestBody Map<String, Object> body) throws IOException {

        String markdown = (String) body.getOrDefault("markdown", "");
        ConvertOptions options = new ConvertOptions();
        if (body.containsKey("pageSize"))          options.setPageSize((String) body.get("pageSize"));
        if (body.containsKey("includeImages"))     options.setIncludeImages((Boolean) body.get("includeImages"));
        if (body.containsKey("linkStrategy"))      options.setLinkStrategy((String) body.get("linkStrategy"));
        if (body.containsKey("generateToc"))       options.setGenerateToc((Boolean) body.get("generateToc"));

        ConvertResult result = convertService.convertText(markdown, format, options);

        return ResponseEntity.ok(Map.of(
            "jobId", result.getJobId(),
            "downloadUrl", result.getDownloadUrl(),
            "fileName", result.getFileName(),
            "warnings", result.getWarnings()
        ));
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
     * Markdown HTML 미리보기
     * POST /api/preview
     */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, String>> preview(@RequestBody Map<String, String> body) {
        String markdown = body.getOrDefault("markdown", "");
        String html = convertService.preview(markdown);
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

    private ConvertOptions buildOptions(String pageSize, boolean includeImages, String linkStrategy,
                                        int remoteImageTimeout, boolean generateToc) {
        ConvertOptions opts = new ConvertOptions();
        opts.setPageSize(pageSize);
        opts.setIncludeImages(includeImages);
        opts.setLinkStrategy(linkStrategy);
        opts.setRemoteImageTimeout(remoteImageTimeout);
        opts.setGenerateToc(generateToc);
        return opts;
    }
}
