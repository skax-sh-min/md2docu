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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
    private final DocxToMarkdownConverter docxToMarkdownConverter;
    private final ZipExtractor zipExtractor;

    @Value("${app.convert.temp-expiry-seconds:3600}")
    private long tempExpirySeconds;

    private static final int MAX_STORE_SIZE = 500;

    // jobId → (result, createdAt)
    private final Map<String, StoredResult> store = new ConcurrentHashMap<>();

    public ConvertService(MarkdownService markdownService, PdfConverter pdfConverter,
                          DocxConverter docxConverter, DocxToMarkdownConverter docxToMarkdownConverter,
                          ZipExtractor zipExtractor) {
        this.markdownService = markdownService;
        this.pdfConverter = pdfConverter;
        this.docxConverter = docxConverter;
        this.docxToMarkdownConverter = docxToMarkdownConverter;
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

    // ── DOCX → Markdown 변환 ─────────────────────────────────────────────────

    public ConvertResult convertDocxToMd(MultipartFile file, boolean splitByChapter) throws IOException {
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.docx";
        if (!name.toLowerCase().endsWith(".docx")) {
            throw new IOException("지원하지 않는 파일 형식입니다. .docx 파일만 지원합니다.");
        }
        List<ConvertWarning> warnings = new ArrayList<>();
        DocxToMarkdownConverter.Output output = docxToMarkdownConverter.convert(file.getBytes(), warnings);

        String base = baseName(name);
        ConvertResult result = new ConvertResult();
        result.setJobId(UUID.randomUUID().toString());
        result.setWarnings(warnings);
        result.setDownloadUrl("/api/download/" + result.getJobId());

        if (splitByChapter) {
            Map<String, byte[]> entries = buildChapterEntries(output, base);
            result.setFileBytes(createZip(entries));
            result.setFileName(base + ".zip");
            result.setContentType("application/zip");
        } else if (output.images().isEmpty()) {
            result.setFileBytes(output.markdown().getBytes(StandardCharsets.UTF_8));
            result.setFileName(base + ".md");
            result.setContentType("text/markdown; charset=UTF-8");
        } else {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put(base + ".md", output.markdown().getBytes(StandardCharsets.UTF_8));
            entries.putAll(output.images());
            result.setFileBytes(createZip(entries));
            result.setFileName(base + ".zip");
            result.setContentType("application/zip");
        }
        return storeResult(result);
    }

    private Map<String, byte[]> buildChapterEntries(DocxToMarkdownConverter.Output output, String base) {
        String[] lines = output.markdown().split("\n", -1);

        List<Integer> h2Positions = new ArrayList<>();
        List<String> h2Titles    = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("## ")) {
                h2Positions.add(i);
                h2Titles.add(lines[i].substring(3).trim());
            }
        }

        Map<String, byte[]> entries = new LinkedHashMap<>();

        if (h2Positions.isEmpty()) {
            // ## 없으면 단일 파일로 처리
            entries.put(base + ".md", output.markdown().getBytes(StandardCharsets.UTF_8));
            entries.putAll(output.images());
            return entries;
        }

        // ── 표지 파일 (## 이전 내용 + 목차) ─────────────────────────────────
        StringBuilder cover = new StringBuilder();
        for (int i = 0; i < h2Positions.get(0); i++) {
            cover.append(lines[i]).append("\n");
        }
        List<String> chapterFiles = new ArrayList<>();
        for (int i = 0; i < h2Titles.size(); i++) {
            chapterFiles.add(String.format("%02d_%s.md", i + 1, toSlug(h2Titles.get(i))));
        }

        cover.append("\n## 목차\n\n");
        for (int i = 0; i < h2Titles.size(); i++) {
            cover.append(String.format("%d. [%s](%s)\n", i + 1, h2Titles.get(i), chapterFiles.get(i)));
        }
        entries.put(base + ".md", cover.toString().getBytes(StandardCharsets.UTF_8));

        // ── 챕터 파일 ────────────────────────────────────────────────────────
        for (int i = 0; i < h2Positions.size(); i++) {
            int from = h2Positions.get(i);
            int to   = (i + 1 < h2Positions.size()) ? h2Positions.get(i + 1) : lines.length;
            StringBuilder chapter = new StringBuilder();
            for (int j = from; j < to; j++) {
                chapter.append(lines[j]).append("\n");
            }
            entries.put(chapterFiles.get(i), chapter.toString().getBytes(StandardCharsets.UTF_8));
        }

        // ── 이미지 ────────────────────────────────────────────────────────────
        entries.putAll(output.images());
        return entries;
    }

    private String toSlug(String title) {
        String slug = title.trim()
            .replaceAll("[\\s]+", "_")
            .replaceAll("[/\\\\:*?\"<>|]", "")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        if (slug.isEmpty()) return "chapter";
        return slug.length() > 50 ? slug.substring(0, 50) : slug;
    }

    private byte[] createZip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    // ── ZIP 변환 ─────────────────────────────────────────────────────────────

    private ConvertResult convertZip(byte[] zipBytes, String format, ConvertOptions options) throws IOException {
        ZipExtractor.ExtractionResult extraction = zipExtractor.extract(zipBytes);
        Path basePath = extraction.baseDir();
        Path mdFile = extraction.mainMdFile();

        try {
            if (mdFile == null) throw new IOException("ZIP 파일 안에 .md 파일이 없습니다.");
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
        String html = markdownService.toHtml(markdown, options.isGenerateToc(), options.isNumberHeadings());

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

        return storeResult(result);
    }

    private ConvertResult storeResult(ConvertResult result) {
        if (store.size() >= MAX_STORE_SIZE) {
            store.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().createdAt()))
                .map(Map.Entry::getKey)
                .ifPresent(store::remove);
        }
        store.put(result.getJobId(), new StoredResult(result, Instant.now()));
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

    public String preview(String markdown, boolean numberHeadings) {
        String html = markdownService.toHtml(markdown, false, numberHeadings);
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
