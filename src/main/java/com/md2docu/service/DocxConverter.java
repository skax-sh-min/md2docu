package com.md2docu.service;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertWarning;
import com.md2docu.model.UserSettings;
import com.md2docu.util.ImageResolver;
import com.md2docu.util.ImageResolver.LocalImagePaths;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
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

    public byte[] convert(String html, ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            setupDefaultStyles(doc, options);

            int[] bkId = {0};
            Document jsoupDoc = Jsoup.parse(html);
            processBlockNodes(doc, jsoupDoc.body().childNodes(), options, imagePaths, warnings, bkId);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    // ── 블록 요소 처리 ────────────────────────────────────────────────────────

    private void processBlockNodes(XWPFDocument doc, List<Node> nodes,
                                   ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings,
                                   int[] bkId) {
        for (Node node : nodes) {
            if (node instanceof TextNode tn) {
                String text = tn.text().trim();
                if (!text.isEmpty()) {
                    XWPFParagraph p = doc.createParagraph();
                    p.createRun().setText(text);
                }
            } else if (node instanceof Element el) {
                processBlockElement(doc, el, options, imagePaths, warnings, bkId);
            }
        }
    }

    private void processBlockElement(XWPFDocument doc, Element el,
                                     ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings,
                                     int[] bkId) {
        switch (el.tagName()) {
            case "h1" -> addHeading(doc, el, 1, options, imagePaths, warnings, bkId);
            case "h2" -> addHeading(doc, el, 2, options, imagePaths, warnings, bkId);
            case "h3" -> addHeading(doc, el, 3, options, imagePaths, warnings, bkId);
            case "h4" -> addHeading(doc, el, 4, options, imagePaths, warnings, bkId);
            case "h5" -> addHeading(doc, el, 5, options, imagePaths, warnings, bkId);
            case "h6" -> addHeading(doc, el, 6, options, imagePaths, warnings, bkId);
            case "p"  -> {
                XWPFParagraph p = doc.createParagraph();
                p.setSpacingBetween(1.2);
                p.setSpacingAfter(Twips.SPACE_PARA_AFTER);
                processInlineNodes(doc, p, el.childNodes(), options, imagePaths, warnings, new RunState());
            }
            case "pre" -> addCodeBlock(doc, el);
            case "ul" -> addList(doc, el, false, 1, options, imagePaths, warnings);
            case "ol" -> addList(doc, el, true, 1, options, imagePaths, warnings);
            case "blockquote" -> addBlockquote(doc, el, options, imagePaths, warnings);
            case "table" -> addTable(doc, el, options, imagePaths, warnings);
            case "hr" -> addHorizontalLine(doc);
            case "img" -> addImageParagraph(doc, el, options, imagePaths, warnings);
            default -> {
                if (!el.childNodes().isEmpty()) {
                    boolean isToc = el.hasClass("toc");
                    if (isToc) {
                        addSpacerParagraph(doc, Twips.SPACE_TOC_BEFORE);
                        addTocTitle(doc);
                    }
                    processBlockNodes(doc, el.childNodes(), options, imagePaths, warnings, bkId);
                    if (isToc) addSpacerParagraph(doc, Twips.SPACE_TOC_AFTER);
                }
            }
        }
    }

    // ── 인라인 요소 처리 ──────────────────────────────────────────────────────

    private void processInlineNodes(XWPFDocument doc, XWPFParagraph para, List<Node> nodes,
                                    ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings,
                                    RunState state) {
        for (Node node : nodes) {
            if (node instanceof TextNode tn) {
                String text = tn.getWholeText();
                if (!text.isEmpty()) {
                    applyRun(para, text, state);
                }
            } else if (node instanceof Element el) {
                processInlineElement(doc, para, el, options, imagePaths, warnings, state);
            }
        }
    }

    private void processInlineElement(XWPFDocument doc, XWPFParagraph para, Element el,
                                      ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings,
                                      RunState state) {
        switch (el.tagName()) {
            case "strong", "b" ->
                processInlineNodes(doc, para, el.childNodes(), options, imagePaths, warnings, state.withBold(true));
            case "em", "i" ->
                processInlineNodes(doc, para, el.childNodes(), options, imagePaths, warnings, state.withItalic(true));
            case "del", "s" ->
                processInlineNodes(doc, para, el.childNodes(), options, imagePaths, warnings, state.withStrike(true));
            case "code" ->
                processInlineNodes(doc, para, el.childNodes(), options, imagePaths, warnings, state.withCode(true));
            case "a" -> addHyperlink(doc, para, el, options, warnings, state);
            case "br" -> {
                XWPFRun run = para.createRun();
                run.addBreak();
            }
            case "img" -> addInlineImage(para, el, options, imagePaths, warnings);
            default ->
                processInlineNodes(doc, para, el.childNodes(), options, imagePaths, warnings, state);
        }
    }

    private void applyRun(XWPFParagraph para, String text, RunState state) {
        XWPFRun run = para.createRun();
        configureRun(run, state);
        run.setText(text);
    }

    private void configureRun(XWPFRun run, RunState state) {
        UserSettings s = settingsService.get();
        if (state.headingSize == null || state.bold) run.setBold(state.bold);
        run.setItalic(state.italic);
        if (state.strike) run.setStrikeThrough(true);
        if (state.code) {
            run.setFontFamily("Courier New");
            run.setFontSize(state.headingSize != null ? state.headingSize : s.getCodeFontSizePt());
        } else {
            run.setFontFamily(primaryFont(s.getBodyFontFamily()));
            run.setFontSize(state.headingSize != null ? state.headingSize : s.getBodyFontSizePt());
        }
        if (state.color != null) run.setColor(state.color);
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

    private void addHeading(XWPFDocument doc, Element el, int level, ConvertOptions options,
                            LocalImagePaths imagePaths, List<ConvertWarning> warnings, int[] bkId) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + level);
        p.setSpacingBefore(Twips.SPACE_H_BEFORE);
        p.setSpacingAfter(Twips.SPACE_H_AFTER);
        p.setSpacingBetween(1.2);

        UserSettings s = settingsService.get();
        int size = headingSize(level, s);
        String id = el.attr("id");
        int bid = bkId[0]++;
        if (!id.isEmpty()) {
            CTBookmark bookmark = p.getCTP().addNewBookmarkStart();
            bookmark.setId(BigInteger.valueOf(bid));
            bookmark.setName(id);
        }
        RunState state = new RunState();
        state.headingSize = size;
        processInlineNodes(doc, p, el.childNodes(), options, imagePaths, warnings, state);
        if (!id.isEmpty()) {
            p.getCTP().addNewBookmarkEnd().setId(BigInteger.valueOf(bid));
        }
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
                         ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings) {
        if (depth > 9) throw new IllegalArgumentException("DOCX lists support at most 9 levels");
        BigInteger start = ordered && listEl.hasAttr("start")
            ? new BigInteger(listEl.attr("start")) : BigInteger.ONE;
        BigInteger numId = createListNumbering(doc, ordered, depth, start);
        for (Element li : listEl.select("> li")) {
            XWPFParagraph p = listParagraph(doc, depth, numId);
            for (Node child : li.childNodes()) {
                if (child instanceof Element e && (e.tagName().equals("ul") || e.tagName().equals("ol"))) {
                    addList(doc, e, e.tagName().equals("ol"), depth + 1, options, imagePaths, warnings);
                    p = null;
                } else if (child instanceof Element e && e.tagName().equals("p")) {
                    if (p == null || !p.getRuns().isEmpty()) p = listParagraph(doc, depth, null);
                    processInlineNodes(doc, p, e.childNodes(), options, imagePaths, warnings, new RunState());
                    p = null;
                } else {
                    if (child instanceof TextNode text && text.isBlank()
                            && (p == null || p.getRuns().isEmpty())) continue;
                    if (p == null) p = listParagraph(doc, depth, null);
                    processInlineNodes(doc, p, List.of(child), options, imagePaths, warnings, new RunState());
                }
            }
        }
    }

    private XWPFParagraph listParagraph(XWPFDocument doc, int depth, BigInteger numId) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("ListParagraph");
        p.setIndentationLeft(Twips.INDENT_BLOCK * depth);
        p.setSpacingBetween(1.2);
        p.setSpacingAfter(Twips.SPACE_LIST_AFTER);
        if (numId != null) {
            p.setNumID(numId);
            p.setNumILvl(BigInteger.valueOf(depth - 1));
            p.setIndentationHanging(Twips.INDENT_BLOCK / 2);
        }
        return p;
    }

    private BigInteger createListNumbering(XWPFDocument doc, boolean ordered, int depth, BigInteger start) {
        XWPFNumbering numbering = doc.getNumbering() != null ? doc.getNumbering() : doc.createNumbering();
        CTAbstractNum definition = CTAbstractNum.Factory.newInstance();
        definition.setAbstractNumId(BigInteger.valueOf(numbering.getAbstractNums().size()));
        definition.addNewMultiLevelType().setVal(STMultiLevelType.MULTILEVEL);
        for (int level = 0; level < 9; level++) {
            CTLvl lvl = definition.addNewLvl();
            lvl.setIlvl(BigInteger.valueOf(level));
            lvl.addNewStart().setVal(level == depth - 1 ? start : BigInteger.ONE);
            lvl.addNewNumFmt().setVal(ordered ? STNumberFormat.DECIMAL : STNumberFormat.BULLET);
            lvl.addNewLvlText().setVal(ordered ? "%" + (level + 1) + "." : "•");
            CTInd indent = lvl.addNewPPr().addNewInd();
            indent.setLeft(BigInteger.valueOf(Twips.INDENT_BLOCK * (level + 1)));
            indent.setHanging(BigInteger.valueOf(Twips.INDENT_BLOCK / 2));
        }
        return numbering.addNum(numbering.addAbstractNum(new XWPFAbstractNum(definition)));
    }

    // ── 인용문 ────────────────────────────────────────────────────────────────

    private void addBlockquote(XWPFDocument doc, Element el,
                               ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(Twips.INDENT_BLOCK);

        CTPPr pPr = p.getCTP().getPPr() != null ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTBorder left = pPr.addNewPBdr().addNewLeft();
        left.setVal(STBorder.SINGLE);
        left.setSz(BigInteger.valueOf(Twips.BORDER_QUOTE_SZ));
        left.setColor("CCCCCC");

        RunState state = new RunState();
        state = state.withColor("666666");
        processInlineNodes(doc, p, el.childNodes(), options, imagePaths, warnings, state);
    }

    // ── 표 ────────────────────────────────────────────────────────────────────

    private void addTable(XWPFDocument doc, Element tableEl,
                          ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings) {
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
                processInlineNodes(doc, p, cells.get(c).childNodes(), options, imagePaths, warnings, state);

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
                                   ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings) {
        if (!options.isIncludeImages()) return;
        String src = imgEl.attr("src");
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        embedImage(p, src, options, imagePaths, warnings);
    }

    private void addInlineImage(XWPFParagraph para, Element imgEl,
                                ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings) {
        if (!options.isIncludeImages()) return;
        String src = imgEl.attr("src");
        embedImage(para, src, options, imagePaths, warnings);
    }

    private void embedImage(XWPFParagraph para, String src,
                            ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings) {
        try {
            byte[] bytes = imageResolver.resolveToBytes(src, imagePaths, warnings, options.getRemoteImageTimeout());
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
                XWPFHyperlinkRun run = new XWPFHyperlinkRun(link, ctr, para);
                configureRun(run, state.withColor("0563C1"));
                run.setUnderline(UnderlinePatterns.SINGLE);

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
                XWPFHyperlinkRun run = new XWPFHyperlinkRun(link, ctr, para);
                configureRun(run, state.withColor("0563C1"));
                run.setUnderline(UnderlinePatterns.SINGLE);
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
        XWPFStyles styles = doc.createStyles();
        CTStyle normal = CTStyle.Factory.newInstance();
        normal.setStyleId("Normal");
        normal.setType(STStyleType.PARAGRAPH);
        normal.setDefault(true);
        normal.addNewName().setVal("Normal");
        styles.addStyle(new XWPFStyle(normal));
        CTStyle list = CTStyle.Factory.newInstance();
        list.setStyleId("ListParagraph");
        list.setType(STStyleType.PARAGRAPH);
        list.addNewName().setVal("List Paragraph");
        list.addNewBasedOn().setVal("Normal");
        styles.addStyle(new XWPFStyle(list));
        UserSettings settings = settingsService.get();
        for (int level = 1; level <= 6; level++) {
            CTStyle heading = CTStyle.Factory.newInstance();
            heading.setStyleId("Heading" + level);
            heading.setType(STStyleType.PARAGRAPH);
            heading.addNewName().setVal("heading " + level);
            heading.addNewBasedOn().setVal("Normal");
            heading.addNewNext().setVal("Normal");
            heading.addNewQFormat();
            heading.addNewUiPriority().setVal(BigInteger.valueOf(level));
            CTPPrGeneral properties = heading.addNewPPr();
            properties.addNewOutlineLvl().setVal(BigInteger.valueOf(level - 1));
            properties.addNewKeepNext();
            CTRPr run = heading.addNewRPr();
            run.addNewB();
            run.addNewSz().setVal(BigInteger.valueOf(headingSize(level, settings) * 2L));
            run.addNewSzCs().setVal(BigInteger.valueOf(headingSize(level, settings) * 2L));
            CTFonts fonts = run.addNewRFonts();
            String font = primaryFont(settings.getBodyFontFamily());
            fonts.setAscii(font);
            fonts.setHAnsi(font);
            fonts.setEastAsia(font);
            styles.addStyle(new XWPFStyle(heading));
        }
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
        Integer headingSize;

        RunState withBold(boolean v)   { RunState s = copy(); s.bold = v; return s; }
        RunState withItalic(boolean v) { RunState s = copy(); s.italic = v; return s; }
        RunState withStrike(boolean v) { RunState s = copy(); s.strike = v; return s; }
        RunState withCode(boolean v)   { RunState s = copy(); s.code = v; return s; }
        RunState withColor(String c)   { RunState s = copy(); s.color = c; return s; }

        private RunState copy() {
            RunState s = new RunState();
            s.bold = bold; s.italic = italic; s.strike = strike;
            s.code = code; s.color = color;
            s.headingSize = headingSize;
            return s;
        }
    }
}
