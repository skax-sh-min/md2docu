package com.md2docu.service;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertWarning;
import com.md2docu.model.UserSettings;
import com.md2docu.util.ImageResolver;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class PdfFontsTest {
    @TempDir Path directory;
    private final List<ConvertWarning> warnings = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(strings = {"<p>한글 본문</p>", "<pre>한글 코드</pre>"})
    void continuesAndWarnsForKoreanWithoutUsableFonts(String html) throws IOException {
        byte[] pdf = converter(new FontService(), new UserSettings()).convert(html, new ConvertOptions(), null, warnings);
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
        assertThat(warnings).singleElement().extracting(ConvertWarning::getType).isEqualTo("PDF_KOREAN_FONT_UNAVAILABLE");
    }

    @Test
    void englishDocumentsDoNotReceiveKoreanWarningsForGeneratedImageErrorPlaceholders() throws IOException {
        converter(new FontService(), new UserSettings()).convert(
            "<p>English<img src='missing.png'></p>", new ConvertOptions(), null, warnings);
        assertThat(warnings).singleElement().extracting(ConvertWarning::getType).isEqualTo("IMAGE_NOT_FOUND");
    }

    @Test
    void corruptedFontsProduceAFontWarningWithoutRejectingEnglishOutput() throws IOException {
        Path bad = Files.write(directory.resolve("broken.ttf"), new byte[]{1, 2, 3, 4, 5});
        byte[] pdf = converter(detect(bad + "|Broken"), new UserSettings())
            .convert("<p>Still readable</p>", new ConvertOptions(), null, warnings);
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("Still readable");
        }
        assertThat(warnings).singleElement().extracting(ConvertWarning::getType).isEqualTo("PDF_FONT_UNAVAILABLE");
    }

    @Test
    void removalAfterStartupIsReportedInsteadOfSilentlySkippingRegistration() throws IOException {
        Path latin = directory.resolve("latin.ttf");
        try (var source = getClass().getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertThat(source).isNotNull();
            Files.copy(source, latin);
        }
        FontService fonts = detect(latin + "|Latin");
        Files.delete(latin);
        converter(fonts, new UserSettings()).convert("<p>Text</p>", new ConvertOptions(), null, warnings);
        assertThat(warnings).singleElement().extracting(ConvertWarning::getType).isEqualTo("PDF_FONT_UNAVAILABLE");
    }

    @Test
    void embedsRegularAndBoldKoreanTtfFacesAndRendersKoreanCodeBlocks() throws IOException {
        Path regular = installed("test.pdf.korean.regular", "C:\\Windows\\Fonts\\malgun.ttf");
        Path bold = installed("test.pdf.korean.bold", "C:\\Windows\\Fonts\\malgunbd.ttf");
        FontService fonts = detect(regular + "|Verified Korean," + bold + "|Verified Korean");
        assertThat(fonts.getStatus().fonts()).extracting(FontService.FontInfo::weight).containsExactly(400, 700);
        assertThat(fonts.getStatus().fonts()).allMatch(FontService.FontInfo::koreanSampleSupported);
        var settings = new UserSettings();
        settings.setBodyFontFamily("'Verified Korean', sans-serif");
        byte[] pdf = converter(fonts, settings).convert(
            "<p>한글 가나다</p><p><strong>굵은 한글</strong></p><pre>코드 한글</pre>",
            new ConvertOptions(), null, warnings);
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("한글 가나다", "굵은 한글", "코드 한글");
            var actualNames = new ArrayList<String>();
            for (var name : document.getPage(0).getResources().getFontNames()) {
                var font = document.getPage(0).getResources().getFont(name);
                assertThat(font.isEmbedded()).isTrue();
                actualNames.add(font.getName());
            }
            for (var face : fonts.getStatus().fonts()) {
                assertThat(actualNames).anyMatch(name -> name.endsWith(face.name()));
            }
            var image = new PDFRenderer(document).renderImageWithDPI(0, 120);
            long darkPixels = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) & 0xFFFFFF) < 0x808080) darkPixels++;
                }
            }
            assertThat(darkPixels).isGreaterThan(500);
            if (Boolean.getBoolean("test.pdf.render-preview")) {
                ImageIO.write(image, "png", Path.of("target", "b12-korean-render.png").toFile());
            }
        }
        assertThat(warnings).isEmpty();
    }

    @Test
    void validatesAndRegistersEachKoreanTtcFaceWithItsOwnMetadata() throws IOException {
        Path collection = installed("test.pdf.korean.collection", "C:\\Windows\\Fonts\\gulim.ttc");
        FontService fonts = detect(collection + "|Verified Collection");
        assertThat(fonts.getStatus().fonts()).hasSizeGreaterThan(1);
        assertThat(fonts.getStatus().koreanSampleSupported()).isTrue();
        var settings = new UserSettings();
        settings.setBodyFontFamily("'Verified Collection', sans-serif");
        byte[] pdf = converter(fonts, settings).convert("<p>한글 가나다</p>", new ConvertOptions(), null, warnings);
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("한글 가나다");
            assertThat(new PDFRenderer(document).renderImageWithDPI(0, 72).getWidth()).isGreaterThan(500);
        }
        assertThat(warnings).isEmpty();
    }

    private static Path installed(String property, String fallback) {
        Path path = Path.of(System.getProperty(property, fallback));
        assumeTrue(Files.isRegularFile(path), () -> "Provide an installed licensed font via -D" + property);
        return path;
    }

    private FontService detect(String raw) {
        var fonts = new FontService();
        ReflectionTestUtils.setField(fonts, "fontPathsRaw", raw);
        fonts.detectFonts();
        return fonts;
    }

    private PdfConverter converter(FontService fonts, UserSettings settings) {
        UserSettingsService service = mock(UserSettingsService.class);
        when(service.get()).thenReturn(settings);
        return new PdfConverter(new ImageResolver(), service, fonts);
    }
}
