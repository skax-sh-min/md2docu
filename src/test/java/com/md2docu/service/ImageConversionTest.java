package com.md2docu.service;

import com.md2docu.TestImages;
import com.md2docu.TestImages.Format;
import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertWarning;
import com.md2docu.model.UserSettings;
import com.md2docu.util.ImageResolver;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageConversionTest {
    private final ConvertOptions options = new ConvertOptions();
    private final UserSettings settings = new UserSettings();
    private final List<ConvertWarning> warnings = new ArrayList<>();
    private final DocxConverter docx;
    private final PdfConverter pdf;

    ImageConversionTest() {
        UserSettingsService service = mock(UserSettingsService.class);
        when(service.get()).thenReturn(settings);
        docx = new DocxConverter(new ImageResolver(), service);
        pdf = new PdfConverter(new ImageResolver(), service, new FontService());
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void embedsCorrectMediaTypesAndPixelsInActualDocxAndPdf(Format format) throws Exception {
        byte[] source = TestImages.bytes(format);
        String html = "<p><img src='" + TestImages.dataUri(source) + "'></p>";
        try (XWPFDocument document = docx(html)) {
            assertThat(document.getAllPictures()).singleElement().satisfies(picture -> {
                assertThat(picture.getPackagePart().getContentType()).isEqualTo(format.outputMime);
                if (format == Format.JPEG) {
                    assertThat(picture.suggestFileExtension()).isIn("jpeg", "jpg");
                } else {
                    assertThat(picture.suggestFileExtension()).isEqualTo(switch (format) {
                        case GIF -> "gif";
                        case BMP -> "bmp";
                        default -> "png";
                    });
                }
            });
            var data = document.getAllPictures().get(0).getData();
            var decoded = ImageIO.read(new ByteArrayInputStream(data));
            assertThat(decoded.getWidth()).isEqualTo(16);
            assertThat(decoded.getHeight()).isEqualTo(10);
            if (format != Format.SVG && format != Format.WEBP) assertThat(data).isEqualTo(source);
            assertThat(extent(document.getParagraphs().get(0)).getWidth()).isPositive();
        }
        try (PDDocument document = pdf(html)) {
            var images = images(document);
            assertThat(images).hasSize(1);
            assertThat(images.get(0).getWidth()).isEqualTo(16);
            assertThat(images.get(0).getHeight()).isEqualTo(10);
            int rgb = images.get(0).getImage().getRGB(8, 5);
            int expected = format == Format.SVG ? 0x2455AA
                : ImageIO.read(new ByteArrayInputStream(source)).getRGB(8, 5);
            assertThat((rgb >>> 16) & 255).isCloseTo((expected >>> 16) & 255, within(3));
            assertThat((rgb >>> 8) & 255).isCloseTo((expected >>> 8) & 255, within(3));
            assertThat(rgb & 255).isCloseTo(expected & 255, within(3));
        }
        assertThat(warnings).isEmpty();
    }

    static Stream<Arguments> failures() throws IOException {
        return Stream.of(
            Arguments.of("missing.png", "IMAGE_NOT_FOUND"),
            Arguments.of("data:image/png;base64,%%==", "IMAGE_INVALID_DATA"),
            Arguments.of("data:image/png;base64,", "IMAGE_INVALID_DATA"),
            Arguments.of(TestImages.dataUri(Arrays.copyOf(TestImages.bytes(Format.PNG), 20)), "IMAGE_INVALID_DATA"),
            Arguments.of(TestImages.dataUri(TestImages.raster("tiff", 16, 10)), "IMAGE_UNSUPPORTED_FORMAT"),
            Arguments.of(TestImages.dataUri(TestImages.svg(3000, 2001, "")), "IMAGE_TOO_LARGE"),
            Arguments.of(TestImages.dataUri(TestImages.svg(16, 10,
                "<image width='16' height='10' xlink:href='file:///not-allowed.png'/>")), "IMAGE_CONVERSION_FAILED")
        );
    }

    @ParameterizedTest
    @MethodSource("failures")
    void preservesSurroundingTextAndReportsEachFailedImageExactlyOnce(String src, String code) throws Exception {
        String html = "<p>BEFORE<img src='" + src + "'>AFTER</p>";
        try (XWPFDocument document = docx(html)) {
            assertThat(document.getAllPictures()).isEmpty();
            assertThat(document.getParagraphs().get(0).getText())
                .contains("BEFORE[", ConvertWarning.imageTarget(src), "]AFTER");
        }
        assertWarning(code, src);
        warnings.clear();
        try (PDDocument document = pdf(html)) {
            assertThat(images(document)).isEmpty();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document).replaceAll("\\s+", "");
            assertThat(text).contains("BEFORE[", ConvertWarning.imageTarget(src).replaceAll("\\s+", ""), "]AFTER");
        }
        assertWarning(code, src);
    }

    @Test
    void excludedImagesDoNotDecodeOrReportFailures() throws Exception {
        options.setIncludeImages(false);
        try (var document = docx("<p><img src='data:image/png;base64,%%=='></p>")) {
            assertThat(document.getAllPictures()).isEmpty();
        }
        try (var document = pdf("<p><img src='data:image/png;base64,%%=='></p>")) {
            assertThat(images(document)).isEmpty();
        }
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A4", "LETTER"})
    void constrainsWideImagesToTheActualPageAndTableCellWidths(String pageSize) throws Exception {
        narrowPage(pageSize);
        String src = TestImages.dataUri(TestImages.raster("png", 2400, 600));
        double pageWidth = "LETTER".equals(pageSize) ? 612 : 595.3;
        double bodyWidth = pageWidth - 30 * 72.0 / 25.4;
        try (var document = docx("<p><img src='" + src + "'></p>")) {
            Rectangle2D image = extent(document.getParagraphs().get(0));
            assertThat(image.getWidth()).isLessThanOrEqualTo(bodyWidth + 1);
            assertThat(image.getWidth() / image.getHeight()).isCloseTo(4, within(0.001));
        }
        String table = "<table><tr><td><img src='" + src + "'></td><td>OTHER CELL</td></tr></table>";
        try (var document = docx(table)) {
            var cell = document.getTables().get(0).getRow(0).getCell(0);
            Rectangle2D image = extent(cell.getParagraphs().get(0));
            assertThat(image.getWidth()).isLessThanOrEqualTo(cell.getWidth() / 20.0 - 10.8 + 0.001);
            assertThat(image.getWidth() / image.getHeight()).isCloseTo(4, within(0.001));
        }
        try (var document = pdf("<p><img src='" + src + "'></p>")) {
            var image = drawnImages(document).get(0);
            assertThat(image.getWidth()).isLessThanOrEqualTo(bodyWidth + 1);
            assertThat(image.getWidth() / image.getHeight()).isCloseTo(4, within(0.01));
        }
        try (var document = pdf(table)) {
            var image = drawnImages(document).get(0);
            assertThat(image.getWidth()).isLessThanOrEqualTo(bodyWidth / 2 - 15 + 1);
            assertThat(image.getWidth() / image.getHeight()).isCloseTo(4, within(0.01));
        }
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A4", "LETTER"})
    void constrainsTallImagesToThePageBodyHeightWithoutDistortingThem(String pageSize) throws Exception {
        narrowPage(pageSize);
        double bodyHeight = ("LETTER".equals(pageSize) ? 792 : 841.9) - 28 * 72.0 / 25.4;
        String html = "<p><img src='" + TestImages.dataUri(TestImages.raster("png", 600, 2400)) + "'></p>";
        try (var document = docx(html)) {
            var image = extent(document.getParagraphs().get(0));
            assertThat(image.getHeight()).isLessThanOrEqualTo(bodyHeight + 1);
            assertThat(image.getWidth() / image.getHeight()).isCloseTo(0.25, within(0.001));
        }
        try (var document = pdf(html)) {
            var image = drawnImages(document).get(0);
            assertThat(image.getHeight()).isLessThanOrEqualTo(bodyHeight + 1);
            assertThat(image.getWidth() / image.getHeight()).isCloseTo(0.25, within(0.001));
        }
        assertThat(warnings).isEmpty();
    }

    @Test
    void subtractsListIndentationAndKeepsThinImagesNonzero() throws Exception {
        narrowPage("LETTER");
        String src = TestImages.dataUri(TestImages.raster("png", 2400, 600));
        try (var document = docx("<ul><li><ul><li><ul><li><ul><li><ul><li><img src='" + src
                + "'></li></ul></li></ul></li></ul></li></ul></li></ul>")) {
            var p = document.getParagraphs().get(4);
            assertThat(extent(p).getWidth()).isLessThanOrEqualTo(612 - 30 * 72.0 / 25.4
                - p.getIndentationLeft() / 20.0 + 1);
        }
        String thin = TestImages.dataUri(TestImages.raster("png", 1, 2000));
        try (var document = docx("<p><img src='" + thin + "'></p>")) {
            assertThat(extent(document.getParagraphs().get(0)).getWidth()).isPositive();
        }
        assertThat(warnings).isEmpty();
    }

    @Test
    void keepsHeaderShadingAndImageWidthInTheSameCellProperties() throws Exception {
        String src = TestImages.dataUri(TestImages.bytes(Format.PNG));
        try (var document = docx("<table><tr><th><img src='" + src + "'></th><th>Other</th></tr></table>")) {
            var cell = document.getTables().get(0).getRow(0).getCell(0);
            assertThat(cell.getCTTc().getTcPr().isSetTcW()).isTrue();
            assertThat(cell.getCTTc().getTcPr().isSetShd()).isTrue();
            assertThat(extent(cell.getParagraphs().get(0)).getWidth()).isPositive();
        }
        assertThat(warnings).isEmpty();
    }

    @Test
    void fitsImagesAfterApplyingNestedQuoteAndQuotedTableIndentation() throws Exception {
        String src = TestImages.dataUri(TestImages.raster("png", 2400, 600));
        try (var document = docx("<blockquote>".repeat(5) + "<p><img src='" + src
            + "'></p>" + "</blockquote>".repeat(5))) {
            var paragraph = document.getParagraphs().get(0);
            assertThat(paragraph.getIndentationLeft()).isEqualTo(3600);
            assertThat(extent(paragraph).getWidth()).isLessThanOrEqualTo(
                595.3 - 30 * 72.0 / 25.4 - paragraph.getIndentationLeft() / 20.0 + 1);
            assertThat(extent(paragraph).getWidth() / extent(paragraph).getHeight()).isCloseTo(4, within(0.001));
        }
        try (var document = docx("<blockquote><table><tr><td><img src='" + src
            + "'></td><td>Other</td></tr></table></blockquote>")) {
            var cell = document.getTables().get(0).getRow(0).getCell(0);
            var paragraph = cell.getParagraphs().get(0);
            assertThat(paragraph.getIndentationLeft()).isEqualTo(720);
            assertThat(extent(paragraph).getWidth()).isLessThanOrEqualTo(
                cell.getWidth() / 20.0 - 10.8 - paragraph.getIndentationLeft() / 20.0 + 0.001);
        }
        assertThat(warnings).isEmpty();
    }

    private void narrowPage(String pageSize) {
        options.setPageSize(pageSize);
        settings.setMarginLeftMm(15);
        settings.setMarginRightMm(15);
        settings.setMarginTopMm(14);
        settings.setMarginBottomMm(14);
    }

    private XWPFDocument docx(String html) throws IOException {
        return new XWPFDocument(new ByteArrayInputStream(docx.convert(html, options, null, warnings)));
    }

    private PDDocument pdf(String html) throws IOException {
        return Loader.loadPDF(pdf.convert(html, options, null, warnings));
    }

    private Rectangle2D extent(XWPFParagraph p) {
        var image = p.getRuns().stream().filter(r -> !r.getEmbeddedPictures().isEmpty())
            .findFirst().orElseThrow().getCTR().getDrawingArray(0).getInlineArray(0).getExtent();
        return new Rectangle2D.Double(0, 0,
            image.getCx() / (double) Units.EMU_PER_POINT, image.getCy() / (double) Units.EMU_PER_POINT);
    }

    private List<PDImageXObject> images(PDDocument document) throws IOException {
        List<PDImageXObject> images = new ArrayList<>();
        for (var page : document.getPages()) {
            for (var name : page.getResources().getXObjectNames()) {
                if (page.getResources().getXObject(name) instanceof PDImageXObject image) images.add(image);
            }
        }
        return images;
    }

    private List<Rectangle2D> drawnImages(PDDocument document) throws IOException {
        List<Rectangle2D> images = new ArrayList<>();
        for (PDPage page : document.getPages()) {
            var graphics = new ImageGeometry(page);
            graphics.processPage(page);
            images.addAll(graphics.images);
        }
        assertThat(images).hasSize(1);
        return images;
    }

    private void assertWarning(String code, String src) {
        assertThat(warnings).singleElement().satisfies(warning -> {
            assertThat(warning.getType()).isEqualTo(code);
            assertThat(warning.getTarget()).isEqualTo(ConvertWarning.imageTarget(src));
        });
    }

    private static class ImageGeometry extends PDFGraphicsStreamEngine {
        final List<Rectangle2D> images = new ArrayList<>();
        ImageGeometry(PDPage page) { super(page); }
        @Override public void drawImage(PDImage image) {
            var matrix = getGraphicsState().getCurrentTransformationMatrix();
            images.add(new Rectangle2D.Double(matrix.getTranslateX(), matrix.getTranslateY(),
                matrix.getScalingFactorX(), matrix.getScalingFactorY()));
        }
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {}
        @Override public void clip(int rule) {}
        @Override public void moveTo(float x, float y) {}
        @Override public void lineTo(float x, float y) {}
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
        @Override public Point2D getCurrentPoint() { return new Point2D.Float(); }
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public void strokePath() {}
        @Override public void fillPath(int rule) {}
        @Override public void fillAndStrokePath(int rule) {}
        @Override public void shadingFill(COSName name) {}
    }
}
