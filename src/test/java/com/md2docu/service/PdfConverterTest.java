package com.md2docu.service;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertWarning;
import com.md2docu.model.UserSettings;
import com.md2docu.util.ImageResolver;
import com.md2docu.util.ImageResolver.LocalImagePaths;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PdfConverterTest {

    @TempDir
    Path basePath;

    private final ConvertOptions options = new ConvertOptions();
    private final List<ConvertWarning> warnings = new ArrayList<>();
    private ImageResolver imageResolver;
    private LocalImagePaths imagePaths;
    private PdfConverter converter;
    private byte[] png;
    private String dataUri;

    @BeforeEach
    void setUp() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x2455AA);
        image.setRGB(1, 0, 0xFFCC00);
        image.setRGB(0, 1, 0x33AA55);
        image.setRGB(1, 1, 0xCC3344);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        png = out.toByteArray();
        dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        Files.createDirectories(basePath.resolve("images"));
        Files.write(basePath.resolve("images").resolve("pixel.png"), png);
        imagePaths = new LocalImagePaths(basePath, basePath);

        imageResolver = spy(new ImageResolver());
        UserSettingsService settingsService = mock(UserSettingsService.class);
        when(settingsService.get()).thenReturn(new UserSettings());
        converter = new PdfConverter(imageResolver, settingsService, new FontService());
    }

    @ParameterizedTest
    @EnumSource(ImageSource.class)
    void embedsTheSamePngFromEverySupportedSource(ImageSource source) throws IOException {
        String src = switch (source) {
            case LOCAL -> "images/pixel.png";
            case ZIP_ROOT -> "/images/pixel.png";
            case BASE64 -> dataUri;
            case REMOTE -> "https://example.com/pixel.png";
            case REMOTE_HTTP -> "http://example.com/pixel.png";
            case REMOTE_UPPERCASE -> "HTTPS://example.com/pixel.png";
        };
        if (source == ImageSource.REMOTE || source == ImageSource.REMOTE_HTTP
                || source == ImageSource.REMOTE_UPPERCASE) {
            doReturn(dataUri).when(imageResolver)
                .resolveToBase64DataUri(anyString(), any(), anyList(), anyInt());
        }

        try (PDDocument pdf = convert("<p><img src='" + src + "' alt='pixel'></p>")) {
            assertPng(pdf);
            assertThat(warnings).isEmpty();
        }
        String resolvedSrc = source == ImageSource.REMOTE_UPPERCASE
            ? "https://example.com/pixel.png" : src;
        verify(imageResolver).resolveToBase64DataUri(resolvedSrc, imagePaths, warnings,
            options.getRemoteImageTimeout());
    }

    @Test
    void resolvesUriEncodedLocalPathsWithoutLosingHtmlEntities() throws IOException {
        Files.write(basePath.resolve("images").resolve("pixel & sample.png"), png);

        try (PDDocument pdf = convert(
                "<IMG SRC='images/pixel%20%26%20sample.png' ALT='One &amp; Two'>")) {
            assertPng(pdf);
            assertThat(warnings).isEmpty();
        }
    }

    @Test
    void passesDecodedRemoteAttributesWithoutChangingUriEscapes() throws IOException {
        doReturn(dataUri).when(imageResolver)
            .resolveToBase64DataUri(anyString(), any(), anyList(), anyInt());
        String src = "https://example.com/pixel%20image.png?one=1&two=2";

        try (PDDocument pdf = convert(
                "<img src='https://example.com/pixel%20image.png?one=1&amp;two=2'>")) {
            assertPng(pdf);
            assertThat(warnings).isEmpty();
        }
        verify(imageResolver).resolveToBase64DataUri(src, imagePaths, warnings,
            options.getRemoteImageTimeout());
    }

    @Test
    void doesNotResolveImagesWhenTheyAreExcluded() throws IOException {
        options.setIncludeImages(false);

        try (PDDocument pdf = convert("<img src='" + dataUri + "'><img src='missing.png'><img>")) {
            assertThat(images(pdf)).isEmpty();
            assertThat(warnings).isEmpty();
        }
        verifyNoInteractions(imageResolver);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<img>", "<img src=''>", "<img src='missing.png'>"})
    void reportsMissingImagesInsteadOfSilentlyDroppingThem(String html) throws IOException {
        try (PDDocument pdf = convert(html)) {
            assertThat(images(pdf)).isEmpty();
            assertThat(warnings).singleElement()
                .extracting(ConvertWarning::getType).isEqualTo("IMAGE_NOT_FOUND");
        }
    }

    @Test
    void reportsLocalImagesWithoutAnUploadBase() throws IOException {
        try (PDDocument pdf = Loader.loadPDF(
                converter.convert("<img src='images/pixel.png'>", options, null, warnings))) {
            assertThat(images(pdf)).isEmpty();
            assertThat(warnings).singleElement().satisfies(warning -> {
                assertThat(warning.getType()).isEqualTo("IMAGE_NOT_FOUND");
                assertThat(warning.getTarget()).isEqualTo("images/pixel.png");
            });
        }
    }

    @Test
    void reportsInvalidBase64Once() throws IOException {
        String src = "data:image/png;base64,invalid-base64";

        try (PDDocument pdf = convert("<img src='" + src + "'>")) {
            assertThat(images(pdf)).isEmpty();
            assertThat(warnings).singleElement().satisfies(warning -> {
                assertThat(warning.getType()).isEqualTo("IMAGE_FETCH_FAILED");
                assertThat(warning.getTarget()).isEqualTo(src);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "javascript:alert(1)",
        "file:///C:/secret.png",
        "jar:file:///app.jar!/pixel.png",
        "ftp://example.com/pixel.png",
        "//example.com/pixel.png",
        "data:text/html;base64,SGVsbG8=",
        "http://example.com:bad/pixel.png",
        "https://user:password@example.com/pixel.png"
    })
    void rejectsUnsupportedImageReferencesBeforeResolvingThem(String src) throws IOException {
        try (PDDocument pdf = convert("<img src='" + src + "'>")) {
            assertThat(images(pdf)).isEmpty();
            assertThat(warnings).singleElement().satisfies(warning -> {
                assertThat(warning.getType()).isEqualTo("UNSUPPORTED_RESOURCE");
                assertThat(warning.getTarget()).isEqualTo(src);
            });
        }
        verifyNoInteractions(imageResolver);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1/pixel.png",
        "http://192.168.1.10/pixel.png",
        "http://169.254.169.254/pixel.png",
        "http://0.0.0.0/pixel.png"
    })
    void retainsTheResolversRemoteAddressRestrictions(String src) throws IOException {
        try (PDDocument pdf = convert("<img src='" + src + "'>")) {
            assertThat(images(pdf)).isEmpty();
            assertThat(warnings).singleElement().satisfies(warning -> {
                assertThat(warning.getType()).isEqualTo("IMAGE_FETCH_FAILED");
                assertThat(warning.getTarget()).isEqualTo(src);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../private.png", "%2e%2e/private.png", "/../private.png"})
    void retainsTheUploadRootBoundaryForDecodedImagePaths(String src) throws IOException {
        Path uploadRoot = Files.createDirectory(basePath.resolve("upload"));
        Files.write(basePath.resolve("private.png"), png);

        try (PDDocument pdf = Loader.loadPDF(
                converter.convert("<img src='" + src + "'>", options,
                    new LocalImagePaths(uploadRoot, uploadRoot), warnings))) {
            assertThat(images(pdf)).isEmpty();
            assertThat(warnings).singleElement()
                .extracting(ConvertWarning::getType).isEqualTo("IMAGE_NOT_FOUND");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "javascript:alert(1)",
        "file:///C:/secret.txt",
        "jar:file:///app.jar!/notes.txt",
        "data:text/html;base64,SGVsbG8=",
        "vbscript:msgbox(1)",
        "//example.com/notes.txt"
    })
    void rejectsUnsupportedLinksWithoutLosingTheirContent(String href) throws IOException {
        try (PDDocument pdf = convert("<p><a href='" + href + "'><strong>Label</strong></a></p>")) {
            assertThat(text(pdf)).contains("Label");
            assertThat(uriTargets(pdf)).isEmpty();
            assertThat(warnings).singleElement().satisfies(warning -> {
                assertThat(warning.getType()).isEqualTo("UNSUPPORTED_RESOURCE");
                assertThat(warning.getTarget()).isEqualTo(href);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"keep", "warn", "ignore"})
    void appliesLinkStrategiesWithoutLosingTextImagesOrAnchors(String strategy) throws IOException {
        options.setLinkStrategy(strategy);
        String local = "files/notes%20%26.txt";
        String external = "https://example.com/notes?one=1&two=2";
        String html = "<p><a href='" + local + "'><strong>Attachment</strong>"
            + "<img src='" + dataUri + "'></a></p>"
            + "<p><a href='https://example.com/notes?one=1&amp;two=2'>External</a></p>"
            + "<p><a href='#chapter&amp;part'>Jump</a></p>"
            + "<h2 id='chapter&amp;part'>Destination</h2>";

        try (PDDocument pdf = convert(html)) {
            assertPng(pdf);
            assertThat(text(pdf)).contains("Attachment", "External", "Jump", "Destination");
            switch (strategy) {
                case "keep" -> {
                    assertThat(uriTargets(pdf)).containsExactlyInAnyOrder(local, external);
                    assertThat(internalTargets(pdf)).hasSize(1);
                    assertThat(warnings).isEmpty();
                }
                case "warn" -> {
                    assertThat(uriTargets(pdf)).containsExactly(external);
                    assertThat(internalTargets(pdf)).hasSize(1);
                    assertThat(warnings).singleElement().satisfies(warning -> {
                        assertThat(warning.getType()).isEqualTo("ATTACHMENT_NOT_FOUND");
                        assertThat(warning.getTarget()).isEqualTo(local);
                    });
                }
                case "ignore" -> {
                    assertThat(uriTargets(pdf)).isEmpty();
                    assertThat(internalTargets(pdf)).isEmpty();
                    assertThat(warnings).isEmpty();
                }
                default -> throw new AssertionError("Unexpected strategy: " + strategy);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com/notes",
        "HTTP://example.com/notes",
        "mailto:team@example.com",
        "ftp://example.com/notes.txt"
    })
    void preservesSupportedExternalLinksWithoutAttachmentWarnings(String href) throws IOException {
        options.setLinkStrategy("warn");

        try (PDDocument pdf = convert("<a href='" + href + "'>External</a>")) {
            assertThat(uriTargets(pdf)).containsExactly(href);
            assertThat(warnings).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"#chapter&amp;part", "#chapter%26part"})
    void matchesEncodedFragmentLinksToDecodedHeadingIds(String href) throws IOException {
        try (PDDocument pdf = convert("<p><a href='" + href + "'>Jump</a></p>"
                + "<h2 id='chapter&amp;part'>Destination</h2>")) {
            assertThat(internalTargets(pdf)).hasSize(1);
            assertThat(internalTargets(pdf).getFirst().getPage().getCOSObject())
                .isEqualTo(pdf.getPage(0).getCOSObject());
            assertThat(warnings).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<a id='named'></a>",
        "<a id='named' href='files/notes.txt'>Attachment</a>"
    })
    void preservesAnchorIdsWhenAnElementHasNoLinkOrItsLinkIsRemoved(String anchor) throws IOException {
        options.setLinkStrategy("warn");

        try (PDDocument pdf = convert("<p><a href='#named'>Jump</a></p>"
                + "<p>" + anchor + "Destination</p>")) {
            assertThat(internalTargets(pdf)).hasSize(1);
            assertThat(text(pdf)).contains("Jump", "Destination");
            if (anchor.contains("href=")) {
                assertThat(warnings).singleElement()
                    .extracting(ConvertWarning::getType).isEqualTo("ATTACHMENT_NOT_FOUND");
            } else {
                assertThat(warnings).isEmpty();
            }
        }
    }

    @Test
    void createsATocDestinationAtTheActualHeading() throws IOException {
        options.setGenerateToc(true);
        options.setNumberHeadings(true);
        String markdown = "# Document\n\n" + "Filler paragraph.\n\n".repeat(45)
            + "## Destination\n\nChapter body.";
        String html = new MarkdownService().toHtml(markdown, true, true);

        try (PDDocument pdf = convert(html)) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            assertThat(internalTargets(pdf)).hasSize(1);
            PDPageXYZDestination destination = internalTargets(pdf).getFirst();
            int pageIndex = pdf.getPages().indexOf(destination.getPage());
            assertThat(pageIndex).isGreaterThan(0);

            List<Float> headingBaselines = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String value, List<TextPosition> positions) throws IOException {
                    if (value.contains("Destination")) {
                        headingBaselines.add(positions.getFirst().getYDirAdj());
                    }
                    super.writeString(value, positions);
                }
            };
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            assertThat(stripper.getText(pdf)).contains("Destination");
            assertThat(headingBaselines).hasSize(1);
            float targetFromTop = destination.getPage().getMediaBox().getHeight() - destination.getTop();
            assertThat(targetFromTop)
                .as("destination should point to the heading, allowing its top margin")
                .isCloseTo(headingBaselines.getFirst(), within(48f));
            assertThat(warnings).isEmpty();
        }
    }

    @Test
    void stillRemovesScriptsStylesAndEventHandlers() throws IOException {
        String html = "<script>REMOVED_SCRIPT</script><style>REMOVED_STYLE</style>"
            + "<p onclick='alert(1)'><strong>Visible</strong></p>";

        try (PDDocument pdf = convert(html)) {
            assertThat(text(pdf)).contains("Visible").doesNotContain("REMOVED_SCRIPT", "REMOVED_STYLE");
            assertThat(uriTargets(pdf)).isEmpty();
            assertThat(warnings).isEmpty();
        }
        verifyNoInteractions(imageResolver);
    }

    private PDDocument convert(String html) throws IOException {
        return Loader.loadPDF(converter.convert(html, options, imagePaths, warnings));
    }

    private void assertPng(PDDocument pdf) throws IOException {
        assertThat(images(pdf)).hasSize(1);
        BufferedImage actual = images(pdf).getFirst().getImage();
        BufferedImage expected = ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertThat(actual.getWidth()).isEqualTo(expected.getWidth());
        assertThat(actual.getHeight()).isEqualTo(expected.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertThat(actual.getRGB(x, y)).isEqualTo(expected.getRGB(x, y));
            }
        }
    }

    private static List<PDImageXObject> images(PDDocument pdf) throws IOException {
        List<PDImageXObject> result = new ArrayList<>();
        for (var page : pdf.getPages()) {
            for (var name : page.getResources().getXObjectNames()) {
                if (page.getResources().getXObject(name) instanceof PDImageXObject image) {
                    result.add(image);
                }
            }
        }
        return result;
    }

    private static Set<String> uriTargets(PDDocument pdf) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        for (var page : pdf.getPages()) {
            for (var annotation : page.getAnnotations()) {
                if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI action) {
                    result.add(action.getURI());
                }
            }
        }
        return result;
    }

    private static List<PDPageXYZDestination> internalTargets(PDDocument pdf) throws IOException {
        List<PDPageXYZDestination> result = new ArrayList<>();
        for (var page : pdf.getPages()) {
            for (var annotation : page.getAnnotations()) {
                if (annotation instanceof PDAnnotationLink link
                        && link.getAction() instanceof PDActionGoTo action
                        && action.getDestination() instanceof PDPageXYZDestination destination) {
                    result.add(destination);
                }
            }
        }
        return result;
    }

    private static String text(PDDocument pdf) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        return stripper.getText(pdf);
    }

    private enum ImageSource {
        LOCAL, ZIP_ROOT, BASE64, REMOTE, REMOTE_HTTP, REMOTE_UPPERCASE
    }
}
