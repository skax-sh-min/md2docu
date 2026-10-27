package com.md2docu.service;

import com.md2docu.TestImages;
import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertWarning;
import com.md2docu.model.UserSettings;
import com.md2docu.util.ImageResolver;
import com.md2docu.util.PageLayout;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutputSettingsTest {
    @ParameterizedTest
    @CsvSource({"docx,A4", "docx,LETTER", "pdf,A4", "pdf,LETTER"})
    void preservesOneDetachedSnapshotIncludingTypographyAndMarginsDuringConversion(String format, String pageSize) throws Exception {
        UserSettings shared = new UserSettings();
        shared.setBodyFontFamily("Arial, sans-serif");
        shared.setBodyFontSizePt(17);
        shared.setCodeFontSizePt(9);
        shared.setLineHeight(2.2);
        shared.setH4FontSizePt(18);
        shared.setH5FontSizePt(14);
        shared.setH6FontSizePt(10);
        shared.setMarginLeftMm(10);
        shared.setMarginRightMm(20);
        shared.setMarginTopMm(10);
        shared.setMarginBottomMm(15);
        var service = mock(UserSettingsService.class);
        when(service.get()).thenReturn(shared);
        var resolver = spy(new ImageResolver());
        doAnswer(invocation -> {
            changeSettings(shared);
            return invocation.callRealMethod();
        }).when(resolver).resolve(anyString(), any(), anyList(), anyInt());
        doAnswer(invocation -> {
            changeSettings(shared);
            return invocation.callRealMethod();
        }).when(resolver).resolveToBase64DataUri(anyString(), any(), anyList(), anyInt());
        var options = new ConvertOptions();
        options.setPageSize(pageSize);
        List<ConvertWarning> warnings = new ArrayList<>();
        String html = "<h4>FOUR</h4><p>BODYONE<br>BODYTWO</p>"
            + "<p><img src='" + TestImages.dataUri(TestImages.bytes(TestImages.Format.PNG)) + "'></p>"
            + "<h5>FIVE</h5><h6>SIX</h6><p>AFTER <code>INLINE</code></p>"
            + "<ul><li>LIST</li></ul><pre><code>CODE</code></pre>"
            + "<blockquote>QUOTE</blockquote><table><tr><td>CELL</td></tr></table>";
        if ("docx".equals(format)) {
            byte[] output = new DocxConverter(resolver, service).convert(html, options, null, warnings);
            try (var doc = new XWPFDocument(new ByteArrayInputStream(output))) {
                for (var p : doc.getParagraphs()) {
                    if (p.getText().isBlank()) continue;
                    assertThat(effectiveLine(p, doc)).isEqualTo("528");
                    if ("Heading4".equals(p.getStyleID())) assertFont(p, 18);
                    if ("Heading5".equals(p.getStyleID())) assertFont(p, 14);
                    if ("Heading6".equals(p.getStyleID())) assertFont(p, 10);
                    if (List.of("BODYONE\nBODYTWO", "LIST", "QUOTE").contains(p.getText())) assertFont(p, 17);
                    if (p.getText().equals("CODE")) assertFont(p, 9);
                }
                var cell = doc.getTables().get(0).getRow(0).getCell(0).getParagraphs().get(0);
                assertThat(effectiveLine(cell, doc)).isEqualTo("528");
                assertFont(cell, 17);
                var margin = doc.getDocument().getBody().getSectPr().getPgMar();
                assertThat(margin.getLeft().toString()).isEqualTo("567");
                assertThat(margin.getRight().toString()).isEqualTo("1134");
                assertThat(margin.getTop().toString()).isEqualTo("567");
                assertThat(margin.getBottom().toString()).isEqualTo("850");
                assertThat(doc.getStyles().getStyle("Heading5").getCTStyle().getRPr().getSzArray(0).getVal().toString())
                    .isEqualTo("28");
            }
        } else {
            byte[] output = new PdfConverter(resolver, service, new FontService()).convert(html, options, null, warnings);
            try (var doc = Loader.loadPDF(output)) {
                TextCapture text = new TextCapture();
                text.getText(doc);
                assertThat(text.line("FOUR").positions().get(0).getFontSize()).isCloseTo(18f, within(0.1f));
                assertThat(text.line("FIVE").positions().get(0).getFontSize()).isCloseTo(14f, within(0.1f));
                assertThat(text.line("SIX").positions().get(0).getFontSize()).isCloseTo(10f, within(0.1f));
                assertThat(text.line("AFTER").positions().get(0).getFontSize()).isCloseTo(17f, within(0.1f));
                var first = text.line("BODYONE").positions().get(0);
                var second = text.line("BODYTWO").positions().get(0);
                assertThat(first.getFontSize()).isCloseTo(17f, within(0.1f));
                assertThat(second.getYDirAdj() - first.getYDirAdj()).isCloseTo(17f * 2.2f, within(0.2f));
                assertThat(first.getXDirAdj()).isCloseTo((float) (10 * 72 / 25.4), within(0.2f));
            }
        }
        verify(service, times(1)).get();
        assertThat(shared.getBodyFontSizePt()).isEqualTo(24);
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A4", "LETTER"})
    void acceptsValidBoundariesButRejectsTheNextMillimeterForEachPaper(String paper) {
        UserSettings settings = new UserSettings();
        settings.setMarginTopMm(14);
        settings.setMarginBottomMm(15);
        PageLayout.validateAll(settings);
        if ("A4".equals(paper)) settings.setMarginLeftMm(16);
        else settings.setMarginTopMm(15);
        assertThatThrownBy(() -> PageLayout.validate(paper, settings))
            .isInstanceOf(PageLayout.InvalidLayoutException.class).hasMessageContaining(paper);
    }

    @Test
    void checksLayoutBeforeFetchingAnyImages() {
        var settings = new UserSettings();
        settings.setMarginLeftMm(100);
        var service = mock(UserSettingsService.class);
        when(service.get()).thenReturn(settings);
        var resolver = mock(ImageResolver.class);
        String html = "<img src='https://example.com/image.png'>";
        assertThatThrownBy(() -> new DocxConverter(resolver, service).convert(
            html, new ConvertOptions(), null, new ArrayList<>()))
            .isInstanceOf(PageLayout.InvalidLayoutException.class);
        assertThatThrownBy(() -> new PdfConverter(resolver, service, new FontService()).convert(
            html, new ConvertOptions(), null, new ArrayList<>()))
            .isInstanceOf(PageLayout.InvalidLayoutException.class);
        verifyNoInteractions(resolver);
    }

    private void changeSettings(UserSettings s) {
        s.setBodyFontFamily("Courier New");
        s.setBodyFontSizePt(24);
        s.setCodeFontSizePt(20);
        s.setLineHeight(1);
        s.setH4FontSizePt(30);
        s.setH5FontSizePt(22);
        s.setH6FontSizePt(20);
        s.setMarginLeftMm(5);
        s.setMarginRightMm(5);
        s.setMarginTopMm(5);
        s.setMarginBottomMm(5);
    }

    private void assertFont(XWPFParagraph p, double size) {
        assertThat(p.getRuns().stream().filter(r -> !r.text().isBlank()).toList())
            .isNotEmpty().allSatisfy(r -> assertThat(r.getFontSizeAsDouble()).isEqualTo(size));
    }

    private String effectiveLine(XWPFParagraph p, XWPFDocument doc) {
        var properties = p.getCTP().getPPr();
        if (properties != null && properties.isSetSpacing() && properties.getSpacing().isSetLine()) {
            return properties.getSpacing().getLine().toString();
        }
        return doc.getStyles().getStyle("Normal").getCTStyle().getPPr().getSpacing().getLine().toString();
    }

    private record Line(String text, List<TextPosition> positions) {}

    private static class TextCapture extends PDFTextStripper {
        final List<Line> lines = new ArrayList<>();
        @Override protected void writeString(String text, List<TextPosition> positions) throws IOException {
            lines.add(new Line(text, List.copyOf(positions)));
            super.writeString(text, positions);
        }
        Line line(String label) {
            return lines.stream().filter(line -> line.text().contains(label)).findFirst().orElseThrow();
        }
    }
}
