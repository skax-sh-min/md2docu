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
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.Path;

@Service
@EnableScheduling
public class ConvertService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private static final long MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024;

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
        String raw  = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.docx";
        int    sep  = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        String name = sep >= 0 ? raw.substring(sep + 1) : raw;
        if (name.isEmpty()) name = "document.docx";
        if (!name.toLowerCase().endsWith(".docx")) {
            throw new IOException("지원하지 않는 파일 형식입니다. .docx 파일만 지원합니다.");
        }
        List<ConvertWarning> warnings = new ArrayList<>();
        DocxToMarkdownConverter.Output output = docxToMarkdownConverter.convert(file.getBytes(), warnings);

        String base = baseName(name).trim()
                          .replaceAll("[/\\\\:*?\"<>|]", "")
                          .replaceAll("\\s+", "_");
        if (base.isEmpty()) base = "document";
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

    // ── URL 변환 ──────────────────────────────────────────────────────────────

    public ConvertResult convertUrl(String url, String format, ConvertOptions options) throws IOException {
        validateUrl(url);
        String rawUrl = resolveRawUrl(url);
        byte[] content = downloadFromUrl(rawUrl);
        requireNotHtml(content);
        String path  = URI.create(rawUrl).getPath();
        String fname = path.substring(Math.max(path.lastIndexOf('/') + 1, 0));
        String base  = fname.isEmpty() ? "document" : baseName(fname);
        if (path.toLowerCase().endsWith(".zip")) {
            return convertZip(content, format, options);
        }
        String markdown = resolveRelativeImageUrls(new String(content, StandardCharsets.UTF_8), rawUrl);
        return convertMarkdown(markdown, format, options, null, base);
    }

    private String resolveRawUrl(String url) {
        // GitHub: .../blob/{ref}/{path} → raw.githubusercontent.com
        if (url.startsWith("https://github.com/") && url.contains("/blob/")) {
            return url.replace("https://github.com/", "https://raw.githubusercontent.com/")
                      .replaceFirst("/blob/", "/");
        }
        // GitLab: .../-/blob/{ref}/{path} → .../-/raw/{ref}/{path}
        if (url.startsWith("https://gitlab.com/") && url.contains("/-/blob/")) {
            return url.replaceFirst("/-/blob/", "/-/raw/");
        }
        return url;
    }

    private static final Pattern IMG_URL_PATTERN =
        Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s\"'<>]+)((?:\\s[^)]*)?)\\)");

    private String resolveRelativeImageUrls(String markdown, String mdUrl) {
        URI base;
        try { base = URI.create(mdUrl); } catch (IllegalArgumentException e) { return markdown; }
        Matcher m = IMG_URL_PATTERN.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String alt  = m.group(1);
            String src  = m.group(2);
            String rest = m.group(3);   // optional title
            String resolved = src;
            if (!src.startsWith("http://") && !src.startsWith("https://") && !src.startsWith("data:")) {
                try { resolved = base.resolve(src).toString(); } catch (IllegalArgumentException ignored) {}
            }
            m.appendReplacement(sb, Matcher.quoteReplacement("![" + alt + "](" + resolved + rest + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void requireNotHtml(byte[] content) throws IOException {
        int len = Math.min(content.length, 512);
        String preview = new String(content, 0, len, StandardCharsets.UTF_8).stripLeading().toLowerCase();
        if (preview.startsWith("<!doctype") || preview.startsWith("<html")) {
            throw new IOException(
                "다운로드한 내용이 HTML 페이지입니다. "
                + "GitHub/GitLab 파일 링크를 사용하거나 Raw URL을 직접 입력해 주세요.");
        }
    }

    private void validateUrl(String url) throws IOException {
        if (url == null || url.isBlank()) throw new IOException("URL을 입력해 주세요.");
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IOException("http:// 또는 https:// URL만 허용합니다.");
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isBlank()) throw new IOException("유효하지 않은 URL입니다.");
            // IPv6 brackets: [::1] → ::1
            String bareHost = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
            // Fast-fail: obvious internal hostnames/IP patterns before DNS lookup
            if (isInternalHost(bareHost.toLowerCase())) {
                throw new IOException("내부 네트워크 주소는 허용되지 않습니다.");
            }
            // Resolve DNS and verify every returned address (blocks nip.io-style and IPv6 SSRF)
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(bareHost);
            } catch (UnknownHostException e) {
                throw new IOException("호스트를 찾을 수 없습니다: " + bareHost);
            }
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()
                        || addr.isMulticastAddress()) {
                    throw new IOException("내부 네트워크 주소는 허용되지 않습니다.");
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("유효하지 않은 URL입니다: " + e.getMessage());
        }
    }

    private boolean isInternalHost(String host) {
        return host.equals("localhost")
            || host.equals("::1")
            || host.matches("127\\.\\d+\\.\\d+\\.\\d+")
            || host.matches("10\\.\\d+\\.\\d+\\.\\d+")
            || host.matches("192\\.168\\.\\d+\\.\\d+")
            || host.matches("169\\.254\\.\\d+\\.\\d+")
            || host.matches("172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+");
    }

    private byte[] downloadFromUrl(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();
        try {
            HttpResponse<InputStream> response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                try (InputStream ignored = response.body()) { /* drain */ }
                throw new IOException(
                    "URL이 리다이렉트됩니다 (HTTP " + status + "). "
                    + "GitHub/GitLab 파일 링크를 사용하거나 Raw URL을 직접 입력해 주세요.");
            }
            if (status < 200 || status >= 300) {
                try (InputStream ignored = response.body()) { /* drain */ }
                throw new IOException("다운로드 실패: HTTP " + status);
            }
            try (InputStream is = response.body()) {
                byte[] buf = is.readNBytes((int) MAX_DOWNLOAD_BYTES + 1);
                if (buf.length > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("파일 크기가 최대 허용량("
                        + (MAX_DOWNLOAD_BYTES / 1024 / 1024) + "MB)을 초과했습니다.");
                }
                return buf;
            }
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("URL 다운로드 시간이 초과되었습니다 (15초).");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("다운로드가 중단되었습니다.");
        }
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
        entries.put("00_" + base + ".md", cover.toString().getBytes(StandardCharsets.UTF_8));

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
