package com.md2docu.service;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertWarning;
import com.md2docu.util.ImageResolver;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DocxConverter {

    private final ImageResolver imageResolver;

    public DocxConverter(ImageResolver imageResolver) {
        this.imageResolver = imageResolver;
    }

    public byte[] convert(String html, ConvertOptions options, Path basePath, List<ConvertWarning> warnings) throws IOException {
        XWPFDocument doc = new XWPFDocument();
        setupDefaultStyles(doc, options);

        int[] bkId = {0};
        Document jsoupDoc = Jsoup.parse(html);
        processBlockNodes(doc, jsoupDoc.body().childNodes(), options, basePath, warnings, bkId);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        doc.close();
        return out.toByteArray();
    }

    // ── 블록 요소 처리 ────────────────────────────────────────────────────────

    private void processBlockNodes(XWPFDocument doc, List<Node> nodes,
                                   ConvertOptions options, Path basePath, List<ConvertWarning> warnings, int[] bkId) {
        for (Node node : nodes) {
            if (node instanceof TextNode tn) {
                String text = tn.text().trim();
                if (!text.isEmpty()) {
                    XWPFParagraph p = doc.createParagraph();
                    p.createRun().setText(text);
                }
            } else if (node instanceof Element el) {
                processBlockElement(doc, el, options, basePath, warnings, bkId);
            }
        }
    }

    private void processBlockElement(XWPFDocument doc, Element el,
                                     ConvertOptions options, Path basePath, List<ConvertWarning> warnings, int[] bkId) {
        switch (el.tagName()) {
            case "h1" -> addHeading(doc, el, 1, bkId);
            case "h2" -> addHeading(doc, el, 2, bkId);
            case "h3" -> addHeading(doc, el, 3, bkId);
            case "h4" -> addHeading(doc, el, 4, bkId);
            case "h5" -> addHeading(doc, el, 5, bkId);
            case "h6" -> addHeading(doc, el, 6, bkId);
            case "p"  -> {
                XWPFParagraph p = doc.createParagraph();
                p.setSpacingBetween(1.6);
                p.setSpacingAfter(80);
                processInlineNodes(doc, p, el.childNodes(), options, basePath, warnings, new RunState());
            }
            case "pre" -> addCodeBlock(doc, el);
            case "ul" -> addList(doc, el, false, 1, options, basePath, warnings);
            case "ol" -> addList(doc, el, true, 1, options, basePath, warnings);
            case "blockquote" -> addBlockquote(doc, el, options, basePath, warnings);
            case "table" -> addTable(doc, el, options, basePath, warnings);
            case "hr" -> addHorizontalLine(doc);
            case "img" -> addImageParagraph(doc, el, options, basePath, warnings);
            default -> {
                if (!el.childNodes().isEmpty()) {
                    boolean isToc = el.hasClass("toc");
                    if (isToc) addSpacerParagraph(doc, 200);
                    processBlockNodes(doc, el.childNodes(), options, basePath, warnings, bkId);
                    if (isToc) addSpacerParagraph(doc, 280);
                }
            }
        }
    }

    // ── 인라인 요소 처리 ──────────────────────────────────────────────────────

    private void processInlineNodes(XWPFDocument doc, XWPFParagraph para, List<Node> nodes,
                                    ConvertOptions options, Path basePath, List<ConvertWarning> warnings,
                                    RunState state) {
        for (Node node : nodes) {
            if (node instanceof TextNode tn) {
                String text = tn.getWholeText();
                if (!text.isEmpty()) {
                    applyRun(para, text, state);
                }
            } else if (node instanceof Element el) {
                processInlineElement(doc, para, el, options, basePath, warnings, state);
            }
        }
    }

    private void processInlineElement(XWPFDocument doc, XWPFParagraph para, Element el,
                                      ConvertOptions options, Path basePath, List<ConvertWarning> warnings,
                                      RunState state) {
        switch (el.tagName()) {
            case "strong", "b" ->
                processInlineNodes(doc, para, el.childNodes(), options, basePath, warnings, state.withBold(true));
            case "em", "i" ->
                processInlineNodes(doc, para, el.childNodes(), options, basePath, warnings, state.withItalic(true));
            case "del", "s" ->
                processInlineNodes(doc, para, el.childNodes(), options, basePath, warnings, state.withStrike(true));
            case "code" ->
                processInlineNodes(doc, para, el.childNodes(), options, basePath, warnings, state.withCode(true));
            case "a" -> addHyperlink(doc, para, el, options, warnings, state);
            case "br" -> {
                XWPFRun run = para.createRun();
                run.addBreak();
            }
            case "img" -> addInlineImage(para, el, options, basePath, warnings);
            default ->
                processInlineNodes(doc, para, el.childNodes(), options, basePath, warnings, state);
        }
    }

    private void applyRun(XWPFParagraph para, String text, RunState state) {
        XWPFRun run = para.createRun();
        run.setBold(state.bold);
        run.setItalic(state.italic);
        if (state.strike) run.setStrikeThrough(true);
        if (state.code) {
            run.setFontFamily("Courier New");
            run.setFontSize(10);
        }
        run.setText(text);
    }

    // ── 제목 ──────────────────────────────────────────────────────────────────

    private void addHeading(XWPFDocument doc, Element el, int level, int[] bkId) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(280);
        p.setSpacingAfter(120);
        p.setSpacingBetween(1.6);

        int[] sizes = {28, 24, 20, 16, 14, 12};
        int size = sizes[Math.min(level - 1, 5)];

        if (level <= 2) {
            CTBorder bottom = p.getCTP().addNewPPr().addNewPBdr().addNewBottom();
            bottom.setVal(STBorder.SINGLE);
            bottom.setSz(BigInteger.valueOf(level == 1 ? 8 : 4));
            bottom.setColor("CCCCCC");
        }

        String id = el.attr("id");
        if (!id.isEmpty()) {
            int bid = bkId[0]++;
            insertWmlElement(p, String.format(
                "<w:bookmarkStart xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
                " w:id=\"%d\" w:name=\"%s\"/>", bid, id));
            XWPFRun run = p.createRun();
            run.setBold(true);
            run.setFontSize(size);
            run.setText(el.text());
            insertWmlElement(p, String.format(
                "<w:bookmarkEnd xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
                " w:id=\"%d\"/>", bid));
        } else {
            XWPFRun run = p.createRun();
            run.setBold(true);
            run.setFontSize(size);
            run.setText(el.text());
        }
    }

    private void insertWmlElement(XWPFParagraph p, String xmlFragment) {
        try {
            XmlObject obj = XmlObject.Factory.parse(xmlFragment);
            try (XmlCursor src = obj.newCursor(); XmlCursor dst = p.getCTP().newCursor()) {
                src.toFirstContentToken();
                dst.toEndToken();
                src.moveXml(dst);
            }
        } catch (Exception ignored) {}
    }

    // ── 코드 블록 ─────────────────────────────────────────────────────────────

    private void addCodeBlock(XWPFDocument doc, Element preEl) {
        String code = preEl.text();
        for (String line : code.split("\n", -1)) {
            XWPFParagraph p = doc.createParagraph();
            p.setIndentationLeft(720);

            CTShd shd = p.getCTP().addNewPPr().addNewShd();
            shd.setFill("F5F5F5");
            shd.setVal(STShd.CLEAR);

            XWPFRun run = p.createRun();
            run.setFontFamily("Courier New");
            run.setFontSize(10);
            run.setText(line.isEmpty() ? " " : line);
        }
    }

    // ── 목록 ──────────────────────────────────────────────────────────────────

    private void addList(XWPFDocument doc, Element listEl, boolean ordered, int depth,
                         ConvertOptions options, Path basePath, List<ConvertWarning> warnings) {
        int index = 1;
        for (Element li : listEl.select("> li")) {
            XWPFParagraph p = doc.createParagraph();
            p.setIndentationLeft(720 * depth);
            p.setSpacingBetween(1.6);
            p.setSpacingAfter(40);

            String prefix = ordered ? (index++) + ". " : "• ";
            p.createRun().setText(prefix);

            List<Node> inlineNodes = li.childNodes().stream()
                .filter(n -> !(n instanceof Element e &&
                               (e.tagName().equals("ul") || e.tagName().equals("ol"))))
                .collect(Collectors.toList());
            processInlineNodes(doc, p, inlineNodes, options, basePath, warnings, new RunState());

            for (Element nested : li.select("> ul, > ol")) {
                addList(doc, nested, nested.tagName().equals("ol"), depth + 1, options, basePath, warnings);
            }
        }
    }

    // ── 인용문 ────────────────────────────────────────────────────────────────

    private void addBlockquote(XWPFDocument doc, Element el,
                               ConvertOptions options, Path basePath, List<ConvertWarning> warnings) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(720);

        CTBorder left = p.getCTP().addNewPPr().addNewPBdr().addNewLeft();
        left.setVal(STBorder.SINGLE);
        left.setSz(BigInteger.valueOf(16));
        left.setColor("CCCCCC");

        RunState state = new RunState();
        state = state.withColor("666666");
        processInlineNodes(doc, p, el.childNodes(), options, basePath, warnings, state);
    }

    // ── 표 ────────────────────────────────────────────────────────────────────

    private void addTable(XWPFDocument doc, Element tableEl,
                          ConvertOptions options, Path basePath, List<ConvertWarning> warnings) {
        var rows = tableEl.select("tr");
        if (rows.isEmpty()) return;

        int cols = rows.get(0).select("th, td").size();
        if (cols == 0) return;

        XWPFTable table = doc.createTable(rows.size(), cols);
        table.setWidth("100%");

        for (int r = 0; r < rows.size(); r++) {
            var cells = rows.get(r).select("th, td");
            XWPFTableRow row = table.getRow(r);
            boolean isHeader = !rows.get(r).select("th").isEmpty();

            for (int c = 0; c < cells.size() && c < cols; c++) {
                XWPFTableCell cell = row.getCell(c);
                if (cell == null) cell = row.addNewTableCell();

                XWPFParagraph p = cell.getParagraphs().get(0);
                RunState state = isHeader ? new RunState().withBold(true) : new RunState();
                processInlineNodes(doc, p, cells.get(c).childNodes(), options, basePath, warnings, state);

                if (isHeader) {
                    CTShd shd = cell.getCTTc().addNewTcPr().addNewShd();
                    shd.setFill("F0F0F0");
                    shd.setVal(STShd.CLEAR);
                }
            }
        }
    }

    private void addSpacerParagraph(XWPFDocument doc, int spacingAfter) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(spacingAfter);
    }

    // ── 수평선 ────────────────────────────────────────────────────────────────

    private void addHorizontalLine(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        CTBorder bottom = p.getCTP().addNewPPr().addNewPBdr().addNewBottom();
        bottom.setVal(STBorder.SINGLE);
        bottom.setSz(BigInteger.valueOf(6));
        bottom.setColor("CCCCCC");
    }

    // ── 이미지 (블록) ────────────────────────────────────────────────────────

    private void addImageParagraph(XWPFDocument doc, Element imgEl,
                                   ConvertOptions options, Path basePath, List<ConvertWarning> warnings) {
        if (!options.isIncludeImages()) return;
        String src = imgEl.attr("src");
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        embedImage(p, src, options, basePath, warnings);
    }

    private void addInlineImage(XWPFParagraph para, Element imgEl,
                                ConvertOptions options, Path basePath, List<ConvertWarning> warnings) {
        if (!options.isIncludeImages()) return;
        String src = imgEl.attr("src");
        embedImage(para, src, options, basePath, warnings);
    }

    private void embedImage(XWPFParagraph para, String src,
                            ConvertOptions options, Path basePath, List<ConvertWarning> warnings) {
        try {
            byte[] bytes = imageResolver.resolveToBytes(src, basePath, warnings, options.getRemoteImageTimeout());
            if (bytes == null) {
                para.createRun().setText("[이미지 로드 실패: " + src + "]");
                return;
            }
            int pictureType = imageResolver.detectPictureType(src);
            XWPFRun run = para.createRun();
            // 최대 너비 400pt, 자동 비율
            run.addPicture(new ByteArrayInputStream(bytes), pictureType, src,
                Units.toEMU(400), Units.toEMU(300));
        } catch (Exception e) {
            para.createRun().setText("[이미지 삽입 오류: " + src + "]");
        }
    }

    // ── 하이퍼링크 ────────────────────────────────────────────────────────────

    private void addHyperlink(XWPFDocument doc, XWPFParagraph para, Element aEl,
                              ConvertOptions options, List<ConvertWarning> warnings, RunState state) {
        String href = aEl.attr("href");
        String text = aEl.text();

        if ("ignore".equals(options.getLinkStrategy())) {
            applyRun(para, text, state);
            return;
        }

        if (href.startsWith("http://") || href.startsWith("https://")) {
            try {
                String rId = doc.getPackagePart()
                    .addExternalRelationship(href, XWPFRelation.HYPERLINK.getRelation())
                    .getId();

                CTHyperlink link = para.getCTP().addNewHyperlink();
                link.setId(rId);

                CTR ctr = link.addNewR();
                CTRPr rpr = ctr.addNewRPr();
                CTColor color = rpr.addNewColor();
                color.setVal("0563C1");
                CTUnderline underline = rpr.addNewU();
                underline.setVal(STUnderline.SINGLE);
                if (state.bold) rpr.addNewB();
                if (state.italic) rpr.addNewI();

                CTText t = ctr.addNewT();
                t.setStringValue(text);
            } catch (Exception e) {
                applyRun(para, text + " (" + href + ")", state);
            }
        } else if (href.startsWith("#")) {
            try {
                CTHyperlink link = para.getCTP().addNewHyperlink();
                link.setAnchor(href.substring(1));
                CTR ctr = link.addNewR();
                CTRPr rpr = ctr.addNewRPr();
                CTColor color = rpr.addNewColor();
                color.setVal("0563C1");
                rpr.addNewU().setVal(STUnderline.SINGLE);
                if (state.bold) rpr.addNewB();
                if (state.italic) rpr.addNewI();
                ctr.addNewT().setStringValue(text);
            } catch (Exception e) {
                applyRun(para, text, state.withColor("0563C1"));
            }
        } else {
            // 로컬 파일 링크
            if ("warn".equals(options.getLinkStrategy())) {
                warnings.add(ConvertWarning.attachmentNotFound(href));
                applyRun(para, text + " [⚠ 첨부파일 미포함]", state);
            } else {
                applyRun(para, text, state);
            }
        }
    }

    // ── 기본 스타일 ──────────────────────────────────────────────────────────

    private void setupDefaultStyles(XWPFDocument doc, ConvertOptions options) {
        CTDocument1 docBody = doc.getDocument();
        CTBody body = docBody.getBody();
        if (body.isSetSectPr()) return;

        CTSectPr sectPr = body.addNewSectPr();
        CTPageSz pgSz = sectPr.addNewPgSz();
        if ("LETTER".equalsIgnoreCase(options.getPageSize())) {
            // Letter: 12240 x 15840 twips (8.5 x 11 in)
            pgSz.setW(BigInteger.valueOf(12240));
            pgSz.setH(BigInteger.valueOf(15840));
        } else {
            // A4: 11906 x 16838 twips
            pgSz.setW(BigInteger.valueOf(11906));
            pgSz.setH(BigInteger.valueOf(16838));
        }

        CTPageMar pgMar = sectPr.addNewPgMar();
        pgMar.setTop(BigInteger.valueOf(1134));
        pgMar.setBottom(BigInteger.valueOf(1134));
        pgMar.setLeft(BigInteger.valueOf(1417));
        pgMar.setRight(BigInteger.valueOf(1417));
    }

    // ── RunState (불변 포맷 상태) ─────────────────────────────────────────────

    private static class RunState {
        boolean bold, italic, strike, code;
        String color;

        RunState withBold(boolean v)   { RunState s = copy(); s.bold = v; return s; }
        RunState withItalic(boolean v) { RunState s = copy(); s.italic = v; return s; }
        RunState withStrike(boolean v) { RunState s = copy(); s.strike = v; return s; }
        RunState withCode(boolean v)   { RunState s = copy(); s.code = v; return s; }
        RunState withColor(String c)   { RunState s = copy(); s.color = c; return s; }

        private RunState copy() {
            RunState s = new RunState();
            s.bold = bold; s.italic = italic; s.strike = strike;
            s.code = code; s.color = color;
            return s;
        }
    }
}
