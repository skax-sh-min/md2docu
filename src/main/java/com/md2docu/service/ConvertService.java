package com.md2docu.service;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertResult;
import com.md2docu.model.ConvertWarning;
import com.md2docu.util.ZipExtractor;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@EnableScheduling
public class ConvertService {

    private final MarkdownService markdownService;
    private final PdfConverter pdfConverter;
    private final DocxConverter docxConverter;
    private final ZipExtractor zipExtractor;

    @Value("${app.convert.temp-expiry-seconds:3600}")
    private long tempExpirySeconds;

    // jobId → (result, createdAt)
    private final Map<String, StoredResult> store = new ConcurrentHashMap<>();

    public ConvertService(MarkdownService markdownService, PdfConverter pdfConverter,
                          DocxConverter docxConverter, ZipExtractor zipExtractor) {
        this.markdownService = markdownService;
        this.pdfConverter = pdfConverter;
        this.docxConverter = docxConverter;
        this.zipExtractor = zipExtractor;
    }

    // ── 파일 업로드 변환 ──────────────────────────────────────────────────────

    public ConvertResult convertFile(MultipartFile file, String format, ConvertOptions options) throws IOException {
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        byte[] bytes = file.getBytes();

        if (name.endsWith(".zip")) {
            return convertZip(bytes, format, options);
        }
        String markdown = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return convertMarkdown(markdown, format, options, null, baseName(name));
    }

    // ── 텍스트 직접 변환 ──────────────────────────────────────────────────────

    public ConvertResult convertText(String markdown, String format, ConvertOptions options) throws IOException {
        return convertMarkdown(markdown, format, options, null, "document");
    }

    // ── ZIP 변환 ─────────────────────────────────────────────────────────────

    private ConvertResult convertZip(byte[] zipBytes, String format, ConvertOptions options) throws IOException {
        ZipExtractor.ExtractionResult extraction = zipExtractor.extract(zipBytes);
        Path basePath = extraction.baseDir();
        Path mdFile = extraction.mainMdFile();

        if (mdFile == null) {
            throw new IOException("ZIP 파일 안에 .md 파일이 없습니다.");
        }

        try {
            String markdown = Files.readString(mdFile);
            return convertMarkdown(markdown, format, options, basePath, baseName(mdFile.getFileName().toString()));
        } finally {
            zipExtractor.cleanup(basePath);
        }
    }

    // ── 핵심 변환 로직 ────────────────────────────────────────────────────────

    private ConvertResult convertMarkdown(String markdown, String format,
                                         ConvertOptions options, Path basePath, String baseName) throws IOException {
        List<ConvertWarning> warnings = new ArrayList<>();
        String html = markdownService.toHtml(markdown, options.isGenerateToc());

        byte[] fileBytes;
        String contentType;
        String fileName;

        if ("docx".equalsIgnoreCase(format)) {
            fileBytes = docxConverter.convert(html, options, basePath, warnings);
            contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            fileName = baseName + ".docx";
        } else {
            fileBytes = pdfConverter.convert(html, options, basePath, warnings);
            contentType = "application/pdf";
            fileName = baseName + ".pdf";
        }

        String jobId = UUID.randomUUID().toString();
        ConvertResult result = new ConvertResult();
        result.setJobId(jobId);
        result.setDownloadUrl("/api/download/" + jobId);
        result.setFileBytes(fileBytes);
        result.setFileName(fileName);
        result.setContentType(contentType);
        result.setWarnings(warnings);

        store.put(jobId, new StoredResult(result, Instant.now()));
        return result;
    }

    // ── 다운로드 ──────────────────────────────────────────────────────────────

    public ConvertResult getResult(String jobId) {
        StoredResult stored = store.get(jobId);
        return stored != null ? stored.result() : null;
    }

    public List<ConvertWarning> getWarnings(String jobId) {
        StoredResult stored = store.get(jobId);
        return stored != null ? stored.result().getWarnings() : List.of();
    }

    // ── 미리보기 ─────────────────────────────────────────────────────────────

    public String preview(String markdown) {
        String html = markdownService.toHtml(markdown, false);
        return Jsoup.clean(html, Safelist.relaxed()
            .addTags("div", "span", "hr", "del")
            .addAttributes("div", "class")
            .addAttributes("span", "class")
            .addAttributes("p", "class")
            .addAttributes("a", "class"));
    }

    // ── 만료된 결과 정리 (1시간마다) ─────────────────────────────────────────

    @Scheduled(fixedDelay = 3600_000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(tempExpirySeconds);
        store.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }

    private String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private record StoredResult(ConvertResult result, Instant createdAt) {}
}
