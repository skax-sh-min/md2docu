package com.md2docu.service;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertWarning;
import com.md2docu.model.UserSettings;
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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

@Service
public class DocxConverter {

    private final ImageResolver imageResolver;
    private final UserSettingsService settingsService;

    private static final int    TOC_TITLE_FONT_SIZE = 12;
    private static final double IMG_PX_TO_PT         = 0.75;
    private static final double IMG_MAX_WIDTH_PT     = 400.0;
    private static final double IMG_MAX_HEIGHT_PT    = 550.0;
    private static final int    IMG_FALLBACK_W_PT    = 400;
    private static final int    IMG_FALLBACK_H_PT    = 300;

    public DocxConverter(ImageResolver imageResolver, UserSettingsService settingsService) {
        this.imageResolver = imageResolver;
        this.settingsService = settingsService;
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
                                   ConvertOptions options, Path basePath, List<ConvertWarning> warnings,
                                   int[] bkId) {
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
                                     ConvertOptions options, Path basePath, List<ConvertWarning> warnings,
                                     int[] bkId) {
        switch (el.tagName()) {
            case "h1" -> addHeading(doc, el, 1, bkId);
            case "h2" -> addHeading(doc, el, 2, bkId);
            case "h3" -> addHeading(doc, el, 3, bkId);
            case "h4" -> addHeading(doc, el, 4, bkId);
            case "h5" -> addHeading(doc, el, 5, bkId);
            case "h6" -> addHeading(doc, el, 6, bkId);
            case "p"  -> {
                XWPFParagraph p = doc.createParagraph();
                p.setSpacingBetween(1.2);
                p.setSpacingAfter(Twips.SPACE_PARA_AFTER);
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
                    if (isToc) {
                        addSpacerParagraph(doc, Twips.SPACE_TOC_BEFORE);
                        addTocTitle(doc);
                    }
                    processBlockNodes(doc, el.childNodes(), options, basePath, warnings, bkId);
                    if (isToc) addSpacerParagraph(doc, Twips.SPACE_TOC_AFTER);
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
        UserSettings s = settingsService.get();
        XWPFRun run = para.createRun();
        run.setBold(state.bold);
        run.setItalic(state.italic);
        if (state.strike) run.setStrikeThrough(true);
        if (state.code) {
            run.setFontFamily("Courier New");
            run.setFontSize(s.getCodeFontSizePt());
        } else {
            run.setFontFamily(primaryFont(s.getBodyFontFamily()));
            run.setFontSize(s.getBodyFontSizePt());
        }
        if (state.color != null) run.setColor(state.color);
        run.setText(text);
    }

    private static String primaryFont(String fontFamily) {
        return fontFamily.split(",")[0].trim().replace("'", "").replace("\"", "");
    }

    private static int headingSize(int level, UserSettings s) {
        return switch (level) {
            case 1 -> s.getH1FontSizePt();
            case 2 -> s.getH2FontSizePt();
            case 3 -> s.getH3FontSizePt();
            case 4 -> s.getH4FontSizePt();
            case 5 -> s.getH5FontSizePt();
            default -> s.getH6FontSizePt();
        };
    }

    private static int mmToTwips(int mm) {
        return (int) Math.round(mm * 1440.0 / 25.4);
    }

    // ── 제목 ──────────────────────────────────────────────────────────────────

    private void addHeading(XWPFDocument doc, Element el, int level, int[] bkId) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(Twips.SPACE_H_BEFORE);
        p.setSpacingAfter(Twips.SPACE_H_AFTER);
        p.setSpacingBetween(1.2);

        UserSettings s = settingsService.get();
        int size = headingSize(level, s);
        String font = primaryFont(s.getBodyFontFamily());
        String text = el.text();

        String id = el.attr("id");
        if (!id.isEmpty()) {
            int bid = bkId[0]++;
            String safeId = id.replace("&", "&amp;").replace("\"", "&quot;")
                              .replace("<", "&lt;").replace(">", "&gt;");
            insertWmlElement(p, String.format(
                "<w:bookmarkStart xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
                " w:id=\"%d\" w:name=\"%s\"/>", bid, safeId));
            XWPFRun run = p.createRun();
            run.setBold(true);
            run.setFontFamily(font);
            run.setFontSize(size);
            run.setText(text);
            insertWmlElement(p, String.format(
                "<w:bookmarkEnd xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
                " w:id=\"%d\"/>", bid));
        } else {
            XWPFRun run = p.createRun();
            run.setBold(true);
            run.setFontFamily(font);
            run.setFontSize(size);
            run.setText(text);
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
            p.setIndentationLeft(Twips.INDENT_BLOCK);

            CTPPr pPr = p.getCTP().getPPr() != null ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
            CTShd shd = pPr.addNewShd();
            shd.setFill("F5F5F5");
            shd.setVal(STShd.CLEAR);

            XWPFRun run = p.createRun();
            run.setFontFamily("Courier New");
            run.setFontSize(settingsService.get().getCodeFontSizePt());
            run.setText(line.isEmpty() ? " " : line);
        }
    }

    // ── 목록 ──────────────────────────────────────────────────────────────────

    private void addList(XWPFDocument doc, Element listEl, boolean ordered, int depth,
                         ConvertOptions options, Path basePath, List<ConvertWarning> warnings) {
        int index = 1;
        for (Element li : listEl.select("> li")) {
            XWPFParagraph p = doc.createParagraph();
            p.setIndentationLeft(Twips.INDENT_BLOCK * depth);
            p.setSpacingBetween(1.2);
            p.setSpacingAfter(Twips.SPACE_LIST_AFTER);

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
        p.setIndentationLeft(Twips.INDENT_BLOCK);

        CTPPr pPr = p.getCTP().getPPr() != null ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTBorder left = pPr.addNewPBdr().addNewLeft();
        left.setVal(STBorder.SINGLE);
        left.setSz(BigInteger.valueOf(Twips.BORDER_QUOTE_SZ));
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

    private void addTocTitle(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(Twips.SPACE_TOC_TITLE);
        XWPFRun run = p.createRun();
        run.setBold(true);
        run.setFontSize(TOC_TITLE_FONT_SIZE);
        run.setText("목차 (Contents)");
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
        bottom.setSz(BigInteger.valueOf(Twips.BORDER_HR_SZ));
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
            int[] emu = calcImageEmu(bytes);
            XWPFRun run = para.createRun();
            run.addPicture(new ByteArrayInputStream(bytes), pictureType, src, emu[0], emu[1]);
        } catch (Exception e) {
            para.createRun().setText("[이미지 삽입 오류: " + src + "]");
        }
    }

    // 이미지 픽셀 → EMU 변환: 최대 400pt 너비/550pt 높이에 비율 유지
    private int[] calcImageEmu(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                double wPt = img.getWidth() * IMG_PX_TO_PT;
                double hPt = img.getHeight() * IMG_PX_TO_PT;
                double scale = Math.min(1.0, Math.min(IMG_MAX_WIDTH_PT / wPt, IMG_MAX_HEIGHT_PT / hPt));
                return new int[]{Units.toEMU((int)(wPt * scale)), Units.toEMU((int)(hPt * scale))};
            }
        } catch (Exception ignored) {}
        return new int[]{Units.toEMU(IMG_FALLBACK_W_PT), Units.toEMU(IMG_FALLBACK_H_PT)};
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
            pgSz.setW(BigInteger.valueOf(Twips.LETTER_WIDTH));
            pgSz.setH(BigInteger.valueOf(Twips.LETTER_HEIGHT));
        } else {
            pgSz.setW(BigInteger.valueOf(Twips.A4_WIDTH));
            pgSz.setH(BigInteger.valueOf(Twips.A4_HEIGHT));
        }

        UserSettings s = settingsService.get();
        CTPageMar pgMar = sectPr.addNewPgMar();
        pgMar.setTop(BigInteger.valueOf(mmToTwips(s.getMarginTopMm())));
        pgMar.setBottom(BigInteger.valueOf(mmToTwips(s.getMarginBottomMm())));
        pgMar.setLeft(BigInteger.valueOf(mmToTwips(s.getMarginLeftMm())));
        pgMar.setRight(BigInteger.valueOf(mmToTwips(s.getMarginRightMm())));
    }

    // ── 레이아웃 상수 ─────────────────────────────────────────────────────────

    private static final class Twips {
        // 1인치 = 1440 twips
        static final int INDENT_BLOCK    = 720;   // 0.5 in — 코드 블록·인용문·목록 기준 들여쓰기
        static final int SPACE_H_BEFORE  = 280;   // 제목 앞 간격
        static final int SPACE_H_AFTER   = 120;   // 제목 뒤 간격
        static final int SPACE_PARA_AFTER  = 80;  // 단락 뒤 간격
        static final int SPACE_LIST_AFTER  = 40;  // 목록 항목 뒤 간격
        static final int SPACE_TOC_BEFORE  = 200; // 목차 블록 앞 여백
        static final int SPACE_TOC_AFTER   = 280; // 목차 블록 뒤 여백
        static final int SPACE_TOC_TITLE   = 100; // 목차 제목 뒤 간격
        static final int A4_WIDTH          = 11906; // 210mm
        static final int A4_HEIGHT         = 16838; // 297mm
        static final int LETTER_WIDTH      = 12240; // 8.5 in
        static final int LETTER_HEIGHT     = 15840; // 11 in
        static final int BORDER_QUOTE_SZ   = 16;    // 인용문 왼쪽 선 굵기 (1/8pt)
        static final int BORDER_HR_SZ      = 6;     // 수평선 굵기 (1/8pt)
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
