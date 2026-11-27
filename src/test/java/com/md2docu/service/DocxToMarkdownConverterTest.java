package com.md2docu.service;

import com.md2docu.model.ConvertWarning;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocxToMarkdownConverterTest {

    private final DocxToMarkdownConverter converter = new DocxToMarkdownConverter();
    private final MarkdownService markdown = new MarkdownService();
    private final List<ConvertWarning> warnings = new ArrayList<>();

    @Test
    void preservesTextImagesAndRepeatedImageOccurrencesInOneRun() throws Exception {
        byte[] image = png();
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            XWPFRun run = doc.createParagraph().createRun();
            run.setText("LEFT");
            picture(run, image);
            run.setText("MIDDLE");
            picture(run, image);
            run.setText("RIGHT");
            output = convert(doc);
        }
        var p = html(output).selectFirst("p");
        assertThat(p.textNodes()).extracting(n -> n.getWholeText()).containsExactly("LEFT", "MIDDLE", "RIGHT");
        assertThat(p.childNodes()).extracting(n -> n.nodeName()).containsExactly("#text", "img", "#text", "img", "#text");
        assertThat(p.select("img").eachAttr("src")).containsExactly("images/image_1.png", "images/image_1.png");
        assertThat(output.images()).hasSize(1);
        assertThat(output.images().get("images/image_1.png")).isEqualTo(image);
        assertThat(warnings).isEmpty();
    }

    @Test
    void preservesManualBreaksTabsAndTrailingBreaks() throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            XWPFRun run = doc.createParagraph().createRun();
            run.setText("A");
            run.addBreak();
            run.setText("B");
            run.addTab();
            run.setText("C");
            run.addCarriageReturn();
            run.addBreak();
            output = convert(doc);
        }
        assertThat(output.markdown()).contains("  \n", "&#9;");
        var p = html(output).selectFirst("p");
        assertThat(p.select("br")).hasSize(3);
        assertThat(p.wholeText()).contains("A", "B\tC");
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "*literal* and **bold**", "`code` [label](https://example.com)", "![image](missing.png)",
        "# heading", "1. numbered", "- bullet", "> quote", "---", "<img src=x> &amp;",
        "\\path\\[brackets] ~strike~ | pipe", "    indented", "trailing  ",
        "https://example.com", "user@example.com", "www.example.com", "wide space\u3000"
    })
    void escapesLiteralTextWithoutCreatingMarkdownStructures(String source) throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(source);
            output = convert(doc);
        }
        var html = html(output);
        assertThat(html.body().children()).hasSize(1);
        assertThat(html.body().child(0).tagName()).isEqualTo("p");
        assertThat(html.selectFirst("p").wholeText()).isEqualTo(source);
        assertThat(html.select("em,strong,code,a,img,blockquote,ul,ol,hr")).isEmpty();
    }

    @Test
    void preservesLiteralHeadingMarkersAndWhitespaceAroundStyledRuns() throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            var p = doc.createParagraph();
            p.setStyle("Heading2");
            p.createRun().setText("[Title] *literal* ");
            var run = p.createRun();
            run.setBold(true);
            run.setText(" Bold ");
            output = convert(doc);
        }
        var heading = html(output).selectFirst("h2");
        assertThat(heading.wholeText()).isEqualTo("[Title] *literal*  Bold ");
        assertThat(heading.select("strong").eachText()).containsExactly("Bold");
        assertThat(heading.select("em,a")).isEmpty();
    }

    @Test
    void mergesIdenticallyFormattedRunsAndSeparatesChangingStylesSafely() throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            var p = doc.createParagraph();
            styled(p, "A", true, false);
            styled(p, "B", true, false);
            styled(p, "C", false, true);
            styled(p, "D", true, true);
            p.createRun().setText(" E");
            output = convert(doc);
        }
        var p = html(output).selectFirst("p");
        assertThat(p.text()).isEqualTo("ABCD E");
        assertThat(p.select("strong").eachText()).containsExactly("AB", "D");
        assertThat(p.select("em").eachText()).containsExactly("C", "D");
        assertThat(p.text()).doesNotContain("*");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com/a(b)?x=1&label=%5Bok%5D",
        "https://example.com/a(b)?q=&copy;&x=1",
        "https://example.com/a%20b#part"
    })
    void escapesLinkLabelsAndDestinationsWithoutDroppingInlineFormatting(String url) throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            var run = doc.createParagraph().createHyperlinkRun(url);
            run.setBold(true);
            run.setText("[Label] *literal*");
            output = convert(doc);
        }
        var link = html(output).selectFirst("a");
        assertThat(link.attr("href")).isEqualTo(url);
        assertThat(link.text()).isEqualTo("[Label] *literal*");
        assertThat(link.select("strong").eachText()).containsExactly("[Label] *literal*");
        assertThat(link.select("em")).isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    void preservesTextAndImageOrderInsideHyperlinks() throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            var run = doc.createParagraph().createHyperlinkRun("https://example.com");
            run.setText("LEFT");
            picture(run, png());
            run.setText("RIGHT");
            output = convert(doc);
        }
        var links = html(output).select("a");
        assertThat(links).isNotEmpty();
        assertThat(links.eachAttr("href")).containsOnly("https://example.com");
        var p = html(output).selectFirst("p");
        assertThat(p.text()).isEqualTo("LEFTRIGHT");
        assertThat(p.select("a img")).hasSize(1);
        assertThat(output.images()).hasSize(1);
    }

    @Test
    void keepsTableSpecialCharactersCodeBreaksTabsAndParagraphBoundaries() throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            var table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("A|B");
            table.getRow(0).getCell(1).setText("*literal*");
            var cell = table.getRow(1).getCell(0);
            var run = cell.getParagraphs().get(0).createRun();
            run.setText("LEFT|");
            run.addBreak();
            run.addTab();
            run.setText("RIGHT");
            cell.addParagraph().createRun().setText("Next paragraph");
            var code = table.getRow(1).getCell(1).getParagraphs().get(0).createRun();
            code.setFontFamily("Courier New");
            code.setText("`a|b`\\");
            output = convert(doc);
        }
        var html = html(output);
        assertThat(html.select("table")).hasSize(1);
        assertThat(html.select("th").eachText()).containsExactly("A|B", "*literal*");
        assertThat(html.select("tr")).hasSize(2);
        assertThat(html.select("td")).hasSize(2);
        assertThat(html.select("td").get(0).select("br")).hasSize(2);
        assertThat(html.select("td").get(0).wholeText()).contains("\tRIGHT", "Next paragraph");
        assertThat(html.selectFirst("td code").wholeText()).isEqualTo("`a|b`\\");
        assertThat(warnings).isEmpty();
    }

    @Test
    void preservesUnsupportedFieldResultsAndReportsTheUnsupportedContainer() throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            var p = doc.createParagraph();
            p.createRun().setText("Before ");
            var field = p.getCTP().addNewFldSimple();
            field.setInstr("DATE");
            field.addNewR().addNewT().setStringValue("Visible result");
            p.createRun().setText(" After");
            output = convert(doc);
        }
        assertThat(html(output).selectFirst("p").text()).isEqualTo("Before Visible result After");
        assertThat(warnings).extracting(ConvertWarning::getType).contains("UNSUPPORTED_DOCX_CONTENT");
    }

    @Test
    void keepsHeadingBreaksInsideTheHeading() throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            var p = doc.createParagraph();
            p.setStyle("Heading2");
            var run = p.createRun();
            run.setText("LEFT");
            run.addBreak();
            run.setText("# RIGHT");
            output = convert(doc);
        }
        var html = html(output);
        assertThat(html.body().children()).hasSize(1);
        assertThat(html.selectFirst("h2").select("br")).hasSize(1);
        assertThat(html.selectFirst("h2").text()).isEqualTo("LEFT # RIGHT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"`literal`", "a``b`c", " padded ", "   ", "*literal* &amp;"})
    void preservesCodeContentAndCombinedFormatting(String source) throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            var run = doc.createParagraph().createRun();
            run.setFontFamily("Courier New");
            run.setBold(true);
            run.setItalic(true);
            run.setStrikeThrough(true);
            run.setText(source);
            output = convert(doc);
        }
        var html = html(output);
        assertThat(html.selectFirst("del strong em code").wholeText()).isEqualTo(source);
        assertThat(warnings).isEmpty();
    }

    @Test
    void preservesMixedListItemContentAndTheFollowingBodyBoundary() throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            var numbering = doc.createNumbering();
            var definition = CTAbstractNum.Factory.newInstance();
            definition.setAbstractNumId(BigInteger.ZERO);
            for (int i = 0; i < 2; i++) {
                var level = definition.addNewLvl();
                level.setIlvl(BigInteger.valueOf(i));
                level.addNewStart().setVal(BigInteger.valueOf(i == 0 ? 10 : 1));
                level.addNewNumFmt().setVal(i == 0 ? STNumberFormat.DECIMAL : STNumberFormat.BULLET);
                level.addNewLvlText().setVal(i == 0 ? "%1." : "-");
            }
            var numId = numbering.addNum(numbering.addAbstractNum(
                new org.apache.poi.xwpf.usermodel.XWPFAbstractNum(definition)));
            var p = doc.createParagraph();
            p.setNumID(numId);
            p.setNumILvl(BigInteger.ZERO);
            var run = p.createRun();
            run.setText("*LEFT*");
            picture(run, png());
            run.addBreak();
            run.addTab();
            run.setText("[RIGHT]");
            var child = doc.createParagraph();
            child.setNumID(numId);
            child.setNumILvl(BigInteger.ONE);
            child.createRun().setText("# Child");
            doc.createParagraph().createRun().setText("Outside *literal*");
            output = convert(doc);
        }
        var html = html(output);
        assertThat(html.select("body > ol")).hasSize(1);
        assertThat(html.selectFirst("ol").attr("start")).isEqualTo("10");
        assertThat(html.select("ol > li img")).hasSize(1);
        assertThat(html.select("ol > li br")).hasSize(1);
        assertThat(html.select("ol > li > ul > li").eachText()).containsExactly("# Child");
        assertThat(html.select("body > p").eachText()).containsExactly("Outside *literal*");
        assertThat(html.select("em,strong,a")).isEmpty();
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a|b", "a\\|b", "a\\\\|b", "&amp;|*x*", "[x](a|b)"})
    void preservesTablePipesBackslashesAndLiteralMarkup(String source) throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            doc.createTable(1, 1).getRow(0).getCell(0).setText(source);
            output = convert(doc);
        }
        var html = html(output);
        assertThat(html.select("th")).hasSize(1);
        assertThat(html.selectFirst("th").wholeText()).isEqualTo(source);
        assertThat(html.select("em,strong,a")).isEmpty();
    }

    @Test
    void reportsUnresolvedLinksWithoutDiscardingTheirVisibleContent() throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            var link = doc.createParagraph().getCTP().addNewHyperlink();
            link.setId("missing-id");
            link.addNewR().addNewT().setStringValue("*Visible*");
            output = convert(doc);
        }
        assertThat(html(output).selectFirst("p").text()).isEqualTo("*Visible*");
        assertThat(html(output).select("a,em")).isEmpty();
        assertThat(warnings).extracting(ConvertWarning::getType).containsExactly("HYPERLINK_UNRESOLVED");
    }

    @Test
    void preservesInternalLinkAddressesAndWarnsThatWordBookmarksAreNotRecreated() throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            var link = doc.createParagraph().getCTP().addNewHyperlink();
            link.setAnchor("section");
            link.addNewR().addNewT().setStringValue("Jump");
            output = convert(doc);
        }
        assertThat(html(output).selectFirst("a").attr("href")).isEqualTo("#section");
        assertThat(html(output).selectFirst("a").text()).isEqualTo("Jump");
        assertThat(warnings).extracting(ConvertWarning::getType).containsExactly("UNSUPPORTED_DOCX_CONTENT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\t", "\n\n"})
    void preservesParagraphsContainingOnlyTabsOrExplicitBreaks(String source) throws Exception {
        DocxToMarkdownConverter.Output output;
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(source);
            output = convert(doc);
        }
        var html = html(output);
        assertThat(html.select("p")).hasSize(1);
        if (source.equals("\t")) assertThat(html.selectFirst("p").wholeText()).isEqualTo("\t");
        else assertThat(html.select("p br")).hasSize(2);
    }

    private void styled(XWPFParagraph p, String text, boolean bold, boolean italic) {
        var run = p.createRun();
        run.setBold(bold);
        run.setItalic(italic);
        run.setText(text);
    }

    private void picture(XWPFRun run, byte[] png) throws Exception {
        run.addPicture(new ByteArrayInputStream(png), Document.PICTURE_TYPE_PNG,
            "image.png", Units.toEMU(2), Units.toEMU(2));
    }

    private byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private DocxToMarkdownConverter.Output convert(XWPFDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        return converter.convert(out.toByteArray(), warnings);
    }

    private org.jsoup.nodes.Document html(DocxToMarkdownConverter.Output output) {
        return Jsoup.parseBodyFragment(markdown.toHtml(output.markdown(), false, false));
    }
}
