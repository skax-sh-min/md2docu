package com.md2docu.controller;

import com.md2docu.model.ConversionRequest;
import com.md2docu.model.ConvertResult;
import com.md2docu.model.ConvertWarning;
import com.md2docu.service.ConvertService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.springframework.web.server.ResponseStatusException;

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
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) String includeImages,
            @RequestParam(required = false) String linkStrategy,
            @RequestParam(required = false) String remoteImageTimeout,
            @RequestParam(required = false) String generateToc,
            @RequestParam(required = false) String numberHeadings) throws IOException {

        String normalizedFormat = ConversionRequest.format(format);
        var options = ConversionRequest.formOptions(
            pageSize, includeImages, linkStrategy, remoteImageTimeout, generateToc, numberHeadings);
        ConvertResult result = convertService.convertFile(file, normalizedFormat, options);

        return ResponseEntity.ok(toResponseMap(result));
    }

    /**
     * 텍스트 직접 입력 → 변환
     * POST /api/convert/{format}/text
     */
    @PostMapping("/convert/{format}/text")
    public ResponseEntity<Map<String, Object>> convertText(
            @PathVariable String format,
            @RequestBody ConversionRequest body) throws IOException {

        String normalizedFormat = ConversionRequest.format(format);
        ConvertResult result = convertService.convertText(body.markdownText(), normalizedFormat, body.options());

        return ResponseEntity.ok(toResponseMap(result));
    }

    /**
     * URL의 Markdown/ZIP 파일 다운로드 후 변환
     * POST /api/convert/{format}/url
     */
    @PostMapping("/convert/{format}/url")
    public ResponseEntity<Map<String, Object>> convertUrl(
            @PathVariable String format,
            @RequestBody ConversionRequest body) throws IOException {

        String normalizedFormat = ConversionRequest.format(format);
        ConvertResult result = convertService.convertUrl(body.sourceUrl(), normalizedFormat, body.options());
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "변환 결과를 찾을 수 없습니다.");
        }

        String encodedName = URLEncoder.encode(result.getFileName(), StandardCharsets.UTF_8)
                                       .replace("+", "%20");

        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
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
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
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
            @RequestParam(required = false) String splitByChapter) throws IOException {
        boolean split = Boolean.TRUE.equals(ConversionRequest.formBoolean(splitByChapter, "splitByChapter"));
        ConvertResult result = convertService.convertDocxToMd(file, split);
        return ResponseEntity.ok(toResponseMap(result));
    }

    /**
     * Markdown HTML 미리보기
     * POST /api/preview
     */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, String>> preview(@RequestBody ConversionRequest body) {
        String html = convertService.preview(body.markdownText(), body.previewNumbering());
        return ResponseEntity.ok(Map.of("html", html));
    }

    /**
     * 변환 경고 목록 조회
     * GET /api/convert/{jobId}/warnings
     */
    @GetMapping("/convert/{jobId}/warnings")
    public ResponseEntity<List<ConvertWarning>> warnings(@PathVariable String jobId) {
        List<ConvertWarning> warnings = convertService.getWarnings(jobId);
        if (warnings == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "변환 결과를 찾을 수 없습니다.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(warnings);
    }

    private Map<String, Object> toResponseMap(ConvertResult r) {
        return Map.of(
            "jobId",       r.getJobId(),
            "downloadUrl", r.getDownloadUrl(),
            "fileName",    r.getFileName(),
            "warnings",    r.getWarnings(),
            "expiresAt",   r.getExpiresAt().toString()
        );
    }

}
