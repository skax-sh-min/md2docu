package com.md2docu.service;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertWarning;
import com.md2docu.model.UserSettings;
import com.md2docu.util.ImageResolver;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocxStructureTest {

    private final MarkdownService markdown = new MarkdownService();
    private final DocxToMarkdownConverter reverse = new DocxToMarkdownConverter();
    private final UserSettings settings = new UserSettings();
    private final List<ConvertWarning> warnings = new ArrayList<>();
    private final DocxConverter converter;

    DocxStructureTest() {
        UserSettingsService service = mock(UserSettingsService.class);
        when(service.get()).thenReturn(settings);
        converter = new DocxConverter(new ImageResolver(), service);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void definesSemanticHeadingStylesAndRoundTripsEveryLevel(int level) throws Exception {
        byte[] bytes = convert("<h" + level + " id='section'>Title</h" + level + ">");
        try (var doc = open(bytes)) {
            var paragraph = doc.getParagraphs().get(0);
            assertThat(paragraph.getStyleID()).isEqualTo("Heading" + level);
            var style = doc.getStyles().getStyle("Heading" + level).getCTStyle();
            assertThat(style.getPPr().getOutlineLvl().getVal()).isEqualTo(BigInteger.valueOf(level - 1));
            assertThat(style.isSetQFormat()).isTrue();
            assertThat(style.getRPr().getSzArray(0).getVal()).isNotNull();
            assertThat(paragraph.getCTP().getBookmarkStartArray(0).getName()).isEqualTo("section");
            assertThat(paragraph.getCTP().getBookmarkEndArray(0).getId())
                .isEqualTo(paragraph.getCTP().getBookmarkStartArray(0).getId());
        }
        var html = roundTrip(bytes);
        assertThat(html.select("h" + level).eachText()).containsExactly("Title");
    }

    @Test
    void preservesHeadingInlineFormattingFontsAndHyperlinks() throws Exception {
        settings.setH2FontSizePt(27);
        byte[] bytes = convert("<h2 id='heading'>Plain <strong>Bold</strong> <em>Italic</em> "
            + "<del>Old</del> <code>code</code> <a href='https://example.com'>Site</a></h2>");
        try (var doc = open(bytes)) {
            var p = doc.getParagraphs().get(0);
            assertThat(p.getText()).isEqualTo("Plain Bold Italic Old code Site");
            assertThat(p.getRuns()).anySatisfy(r -> {
                assertThat(r.text()).isEqualTo("Bold");
                assertThat(r.isBold()).isTrue();
            }).anySatisfy(r -> {
                assertThat(r.text()).isEqualTo("Italic");
                assertThat(r.isItalic()).isTrue();
            }).anySatisfy(r -> {
                assertThat(r.text()).isEqualTo("Old");
                assertThat(r.isStrikeThrough()).isTrue();
            }).anySatisfy(r -> {
                assertThat(r.text()).isEqualTo("code");
                assertThat(r.getFontFamily()).isEqualTo("Courier New");
            });
            assertThat(p.getRuns()).allSatisfy(r -> assertThat(r.getFontSizeAsDouble()).isEqualTo(27.0));
            assertThat(p.getCTP().getHyperlinkArray(0).getId()).isNotBlank();
        }
        assertThat(roundTrip(bytes).selectFirst("h2 a").attr("href")).isEqualTo("https://example.com");
    }

    @Test
    void writesRealMixedMultilevelNumberingWithoutLiteralMarkers() throws Exception {
        byte[] bytes = convert("<ol start='7'><li>Parent<ul><li>Child<ol start='3'><li>Deep</li></ol>"
            + "</li></ul></li><li>Next</li></ol>");
        try (var doc = open(bytes)) {
            assertThat(doc.getParagraphs()).extracting(p -> p.getText())
                .containsExactly("Parent", "Child", "Deep", "Next");
            assertThat(doc.getParagraphs()).extracting(p -> p.getNumIlvl().intValue())
                .containsExactly(0, 1, 2, 0);
            for (var p : doc.getParagraphs()) {
                assertThat(doc.getNumbering().getNum(p.getNumID())).isNotNull();
            }
            assertThat(doc.getParagraphs().get(0).getNumID()).isEqualTo(doc.getParagraphs().get(3).getNumID());
        }
        var html = roundTrip(bytes);
        assertThat(html.select("body > ol").attr("start")).isEqualTo("7");
        assertThat(html.select("ol > li > ul > li > ol").attr("start")).isEqualTo("3");
        assertThat(html.select("body > ol > li").eachText()).containsExactly("Parent Child Deep", "Next");
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 9, 99, 999999999})
    void roundTripsOrderedStartsAndMarkerWidthAwareNesting(int start) throws Exception {
        byte[] bytes = convert("<ol start='" + start + "'><li>Parent<ul><li>Child</li></ul></li>"
            + "<li>Next</li></ol>");
        var html = roundTrip(bytes);
        assertThat(html.select("body > ol")).hasSize(1);
        assertThat(html.select("body > ol > li > ul > li").eachText()).containsExactly("Child");
        assertThat(html.select("body > ol > li")).hasSize(2);
        assertThat(html.selectFirst("ol").attr("start")).isEqualTo(start == 1 ? "" : String.valueOf(start));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ol", "ul"})
    void keepsSeparateAdjacentListsAndNestedRestartsSeparate(String tag) throws Exception {
        byte[] bytes = convert("<" + tag + "><li>First</li></" + tag + ">"
            + "<" + tag + "><li>Restart</li></" + tag + ">");
        var html = roundTrip(bytes);
        assertThat(html.select("body > " + tag)).hasSize(2);
        assertThat(html.select("body > " + tag + " > li").eachText()).containsExactly("First", "Restart");
    }

    @Test
    void keepsLooseListParagraphsAndTextAfterNestedListsInTheirItem() throws Exception {
        byte[] bytes = convert("<ol start='10'><li><p>Parent</p><p>More</p>"
            + "<ul><li>Child</li></ul><p>After child</p></li><li><p>Next</p></li></ol>");
        var html = roundTrip(bytes);
        assertThat(html.select("body > ol > li")).hasSize(2);
        Element first = html.selectFirst("body > ol > li");
        assertThat(first.select("p").eachText()).contains("Parent", "More", "After child");
        assertThat(first.select("ul > li").eachText()).containsExactly("Child");
    }

    @Test
    void supportsAllNineWordListLevelsAndRejectsDeeperStructures() throws Exception {
        String valid = "<ul><li>".repeat(9) + "Deep" + "</li></ul>".repeat(9);
        try (var doc = open(convert(valid))) {
            assertThat(doc.getParagraphs().get(8).getNumIlvl()).isEqualTo(BigInteger.valueOf(8));
        }
        assertThat(roundTrip(convert(valid)).select("ul")).hasSize(9);
        assertThatThrownBy(() -> convert("<ul><li>" + valid + "</li></ul>"))
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400))
            .hasMessageContaining("9");
    }

    @Test
    void roundTripsMarkdownHeadingsAndMixedListsTogether() throws Exception {
        String source = "# Title\n\n## Chapter\n\n7. Parent\n   - Child\n     3. Deep\n8. Next\n\n## Second\n\n- End";
        var html = roundTrip(convert(markdown.toHtml(source, false, false)));
        assertThat(html.select("h1, h2").eachText()).containsExactly("Title", "Chapter", "Second");
        assertThat(html.select("body > ol > li > ul > li > ol > li").eachText()).containsExactly("Deep");
        assertThat(html.select("body > ul > li").eachText()).containsExactly("End");
    }

    @Test
    void preservesInlineSpacesAndNestedListRestartBoundaries() throws Exception {
        byte[] bytes = convert("<ul><li><strong>Bold</strong> <em>Italic</em>"
            + "<ol start='3'><li>First</li></ol><ol start='7'><li>Restart</li></ol></li></ul>");
        var html = roundTrip(bytes);
        assertThat(html.selectFirst("body > ul > li").text()).startsWith("Bold Italic");
        assertThat(html.select("body > ul > li > ol")).hasSize(2);
        assertThat(html.select("body > ul > li > ol").eachAttr("start")).containsExactly("3", "7");
    }

    @Test
    void readsWordStartOverridesAndResumesNumberingAfterBodyParagraphs() throws Exception {
        byte[] bytes;
        try (var doc = open(convert("<ol><li>First</li></ol><p>Outside</p><ol><li>Resumed</li></ol>"))) {
            var first = doc.getParagraphs().get(0);
            doc.getParagraphs().get(2).setNumID(first.getNumID());
            var override = doc.getNumbering().getNum(first.getNumID()).getCTNum().addNewLvlOverride();
            override.setIlvl(BigInteger.ZERO);
            override.addNewStartOverride().setVal(BigInteger.valueOf(7));
            bytes = documentBytes(doc);
        }
        var html = roundTrip(bytes);
        assertThat(html.select("body > ol").eachAttr("start")).containsExactly("7", "8");
        assertThat(html.select("body > p").eachText()).containsExactly("Outside");
    }

    @Test
    void keepsRestartsAcrossBlankWordParagraphs() throws Exception {
        var html = roundTrip(convert("<ol><li>First</li></ol><p></p><ol><li>Restart</li></ol>"));
        assertThat(html.select("body > ol")).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void respectsSharedWordLevelRestartPoliciesWhenTheParentAdvances(int restartAt) throws Exception {
        byte[] bytes;
        try (var doc = open(convert("<ol start='4'><li>Parent<ol><li>Child</li></ol></li>"
                + "<li>Next<ol><li>Reset</li></ol></li></ol>"))) {
            BigInteger numId = doc.getParagraphs().get(0).getNumID();
            doc.getParagraphs().forEach(p -> p.setNumID(numId));
            if (restartAt >= 0) {
                BigInteger abstractId = doc.getNumbering().getNum(numId).getCTNum().getAbstractNumId().getVal();
                doc.getNumbering().getAbstractNum(abstractId).getCTAbstractNum().getLvlArray(1)
                    .addNewLvlRestart().setVal(BigInteger.valueOf(restartAt));
            }
            var override = doc.getNumbering().getNum(numId).getCTNum().addNewLvlOverride();
            override.setIlvl(BigInteger.ONE);
            override.addNewStartOverride().setVal(BigInteger.valueOf(2));
            bytes = documentBytes(doc);
        }
        var html = roundTrip(bytes);
        assertThat(html.select("body > ol").attr("start")).isEqualTo("4");
        assertThat(html.select("body > ol > li > ol").eachAttr("start"))
            .containsExactly("2", restartAt == 0 ? "3" : "2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1000000000"})
    void warnsWhenAWordStartCannotBeRepresentedInMarkdown(String start) throws Exception {
        var html = roundTrip(convert("<ol start='" + start + "'><li>Item</li></ol>"));
        assertThat(html.select("body > ol > li").eachText()).containsExactly("Item");
        assertThat(warnings).extracting(ConvertWarning::getType).containsExactly("UNSUPPORTED_LIST_START");
    }

    @Test
    void warnsForBrokenNumberingInsteadOfSilentlyGuessingBullets() throws Exception {
        byte[] bytes;
        try (var doc = open(convert("<p>Item</p>"))) {
            doc.getParagraphs().get(0).setNumID(BigInteger.valueOf(99));
            bytes = documentBytes(doc);
        }
        assertThat(reverse.convert(bytes, warnings).markdown()).contains("Item");
        assertThat(warnings).extracting(ConvertWarning::getType).containsExactly("LIST_NUMBERING_UNRESOLVED");
    }

    private byte[] documentBytes(XWPFDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        return out.toByteArray();
    }

    private byte[] convert(String html) throws Exception {
        return converter.convert(html, new ConvertOptions(), null, warnings);
    }

    private XWPFDocument open(byte[] bytes) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(bytes));
    }

    private org.jsoup.nodes.Document roundTrip(byte[] bytes) throws Exception {
        return Jsoup.parseBodyFragment(markdown.toHtml(reverse.convert(bytes, warnings).markdown(), false, false));
    }
}
