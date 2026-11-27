package com.md2docu.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.openhtmltopdf.css.constants.IdentValue;
import com.openhtmltopdf.pdfboxout.PDFontSupplier;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import com.md2docu.model.ConvertWarning;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class FontService {
    private static final Logger log = Logger.getLogger(FontService.class.getName());
    public static final String KOREAN_SAMPLE = "가나다라마바사아자차카타파하한글힣";

    @Value("${app.pdf.font.paths:}")
    private String fontPathsRaw = "";

    public record FontInfo(String family, String name, int weight, String style, boolean koreanSampleSupported) {}
    public record FontIssue(String family, String reason) {}
    public record FontStatus(Instant checkedAt, String sample, List<FontInfo> fonts, List<FontIssue> issues) {
        public boolean koreanSampleSupported() {
            return fonts.stream().anyMatch(FontInfo::koreanSampleSupported);
        }
    }
    private record FontFileEntry(String path, String family, List<FontInfo> faces) {}
    private record DetectedFonts(List<FontFileEntry> files, FontStatus status) {}

    private volatile DetectedFonts detected = new DetectedFonts(List.of(),
        new FontStatus(Instant.now(), KOREAN_SAMPLE, List.of(), List.of()));

    @PostConstruct
    public void detectFonts() {
        var files = new ArrayList<FontFileEntry>();
        var issues = new ArrayList<FontIssue>();
        for (String value : fontPathsRaw.split(",")) {
            if (value.isBlank()) continue;
            String[] parts = value.trim().split("\\|", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                issues.add(new FontIssue("(설정 항목)", "INVALID_CONFIGURATION"));
                log.warning("app.pdf.font.paths 항목은 경로|패밀리 형식이어야 합니다.");
                continue;
            }
            String path = parts[0].trim();
            String family = parts[1].trim();
            Path fontPath;
            try {
                fontPath = Path.of(path);
            } catch (java.nio.file.InvalidPathException exception) {
                issues.add(new FontIssue(family, "INVALID_CONFIGURATION"));
                log.log(Level.WARNING, "PDF 글꼴 경로가 올바르지 않습니다.", exception);
                continue;
            }
            if (!Files.isRegularFile(fontPath)) {
                issues.add(new FontIssue(family, "NOT_FOUND"));
                log.info("PDF 글꼴 파일 없음: " + path);
                continue;
            }
            try (FontFile file = FontFile.open(path); PDDocument document = new PDDocument()) {
                var faces = new ArrayList<FontInfo>();
                for (TrueTypeFont font : file.fonts) {
                    try {
                        PDType0Font pdfFont = loadFont(document, font);
                        var metrics = font.getOS2Windows();
                        int weight = metrics == null ? 400 : metrics.getWeightClass();
                        boolean italic = (metrics != null && (metrics.getFsSelection() & 1) != 0)
                            || (font.getPostScript() != null && font.getPostScript().getItalicAngle() != 0);
                        if (weight < 1 || weight > 1000) throw new IOException("유효하지 않은 글꼴 weight: " + weight);
                        String name = font.getName();
                        if (name == null || name.isBlank()) throw new IOException("글꼴 면 이름이 없습니다.");
                        faces.add(new FontInfo(family, name, weight, italic ? "italic" : "normal",
                            supportsKoreanSample(pdfFont)));
                    } catch (IOException | IllegalArgumentException exception) {
                        issues.add(new FontIssue(family, "LOAD_FAILED"));
                        log.log(Level.WARNING, "PDF 글꼴 면 검사 실패: " + path, exception);
                    }
                }
                if (!faces.isEmpty()) files.add(new FontFileEntry(path, family, List.copyOf(faces)));
            } catch (IOException | IllegalArgumentException exception) {
                issues.add(new FontIssue(family, "LOAD_FAILED"));
                log.log(Level.WARNING, "PDF 글꼴 검사 실패: " + path, exception);
            }
        }
        List<FontInfo> faces = files.stream().flatMap(file -> file.faces().stream()).toList();
        detected = new DetectedFonts(List.copyOf(files),
            new FontStatus(Instant.now(), KOREAN_SAMPLE, faces, List.copyOf(issues)));
    }

    public FontStatus getStatus() {
        return detected.status();
    }

    public List<String> getAvailableFamilies() {
        return getStatus().fonts().stream().map(FontInfo::family).distinct().toList();
    }

    public Registration registration() {
        return new Registration(detected);
    }

    private static PDType0Font loadFont(PDDocument document, TrueTypeFont font) throws IOException {
        if (font instanceof OpenTypeFont openType && openType.isPostScript()) {
            throw new IOException("CFF 윤곽선은 지원하지 않습니다. TrueType 윤곽선 글꼴을 사용하세요.");
        }
        return PDType0Font.load(document, font, true);
    }

    private static boolean supportsKoreanSample(PDType0Font font) throws IOException {
        try {
            font.encode(KOREAN_SAMPLE);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static final class Registration implements Closeable {
        private final DetectedFonts snapshot;
        private final List<FontFile> opened = new ArrayList<>();
        private boolean koreanSampleSupported;

        private Registration(DetectedFonts snapshot) {
            this.snapshot = snapshot;
        }

        public boolean koreanSampleSupported() {
            return koreanSampleSupported;
        }

        public void register(PdfBoxRenderer renderer, List<ConvertWarning> warnings) {
            snapshot.status().issues().stream().filter(issue -> !issue.reason().equals("NOT_FOUND"))
                .distinct().forEach(issue -> warnings.add(ConvertWarning.pdfFontUnavailable(issue.family())));
            for (FontFileEntry entry : snapshot.files()) {
                try {
                    FontFile file = FontFile.open(entry.path());
                    opened.add(file);
                    for (FontInfo face : entry.faces()) {
                        TrueTypeFont font = null;
                        for (TrueTypeFont candidate : file.fonts) {
                            if (face.name().equals(candidate.getName())) {
                                font = candidate;
                                break;
                            }
                        }
                        if (font == null) throw new IOException("검사한 글꼴 면을 찾을 수 없습니다.");
                        PDType0Font pdfFont = loadFont(renderer.getPdfDocument(), font);
                        renderer.getFontResolver().addFont(new PDFontSupplier(pdfFont), face.family(), face.weight(),
                            face.style().equals("italic") ? IdentValue.ITALIC : IdentValue.NORMAL, true);
                        koreanSampleSupported |= supportsKoreanSample(pdfFont);
                    }
                } catch (IOException | IllegalArgumentException exception) {
                    log.log(Level.WARNING, "PDF 글꼴 등록 실패: " + entry.path(), exception);
                    warnings.add(ConvertWarning.pdfFontUnavailable(entry.family()));
                }
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (FontFile file : opened) {
                try {
                    file.close();
                } catch (IOException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static final class FontFile implements Closeable {
        private final Closeable source;
        private final List<TrueTypeFont> fonts = new ArrayList<>();

        private FontFile(Closeable source) {
            this.source = source;
        }

        private static FontFile open(String path) throws IOException {
            boolean collection;
            try (var input = new DataInputStream(Files.newInputStream(Path.of(path)))) {
                collection = input.readInt() == 0x74746366;
            }
            if (collection) {
                var source = new TrueTypeCollection(Path.of(path).toFile());
                var file = new FontFile(source);
                try {
                    source.processAllFonts(file.fonts::add);
                    if (file.fonts.isEmpty()) throw new IOException("TTC에 글꼴 면이 없습니다.");
                    return file;
                } catch (IOException | RuntimeException exception) {
                    try { file.close(); } catch (IOException closeFailure) { exception.addSuppressed(closeFailure); }
                    throw exception;
                }
            }
            var source = new RandomAccessReadBufferedFile(path);
            try {
                TrueTypeFont font = new TTFParser().parse(source);
                var file = new FontFile(font);
                file.fonts.add(font);
                return file;
            } catch (IOException | RuntimeException exception) {
                try { source.close(); } catch (IOException closeFailure) { exception.addSuppressed(closeFailure); }
                throw exception;
            }
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
