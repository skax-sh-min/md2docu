package com.md2docu.service;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertWarning;
import com.md2docu.model.UserSettings;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.md2docu.util.ImageResolver;
import com.md2docu.util.ImageResolver.LocalImagePaths;
import com.md2docu.util.ImageResolver.ResolvedImage;
import com.md2docu.util.PageLayout;
import com.md2docu.util.ResourceUri;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DocxConverter {

    private final ImageResolver imageResolver;
    private final UserSettingsService settingsService;

    private static final int    TOC_TITLE_FONT_SIZE = 12;
    private static final double IMG_PX_TO_PT         = 0.75;
    private static final double IMG_MAX_WIDTH_PT     = 400.0;
    private static final double IMG_MAX_HEIGHT_PT    = 550.0;

    public DocxConverter(ImageResolver imageResolver, UserSettingsService settingsService) {
        this.imageResolver = imageResolver;
        this.settingsService = settingsService;
    }

    public byte[] convert(String html, ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings) throws IOException {
        UserSettings snapshot = new UserSettings(settingsService.get());
        PageLayout.validate(options.getPageSize(), snapshot);
        try (XWPFDocument doc = new XWPFDocument()) {
            setupDefaultStyles(doc, options, snapshot);

            Document jsoupDoc = Jsoup.parse(html);
            Bookmarks bookmarks = new Bookmarks(jsoupDoc.body());
            processBlockNodes(doc, jsoupDoc.body().childNodes(), options, imagePaths, warnings, bookmarks, snapshot);
            bookmarks.markBlock(doc, jsoupDoc.body(), 0);
            bookmarks.finish(warnings);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    // ── 블록 요소 처리 ────────────────────────────────────────────────────────

    private void processBlockNodes(XWPFDocument doc, List<Node> nodes,
                                   ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings,
                                   Bookmarks bookmarks, UserSettings settings) {
        for (Node node : nodes) {
            if (node instanceof TextNode tn) {
                String text = tn.text().trim();
                if (!text.isEmpty()) {
                    XWPFParagraph p = doc.createParagraph();
                    formatBlockParagraph(p, tn);
                    applyRun(p, text, new RunState(settings, bookmarks, tn));
                }
            } else if (node instanceof Element el) {
                processBlockElement(doc, el, options, imagePaths, warnings, bookmarks, settings);
            }
        }
    }

    private void processBlockElement(XWPFDocument doc, Element el,
                                     ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings,
                                     Bookmarks bookmarks, UserSettings settings) {
        int firstBodyElement = doc.getBodyElements().size();
        switch (el.tagName()) {
            case "h1" -> addHeading(doc, el, 1, options, imagePaths, warnings, bookmarks, settings);
            case "h2" -> addHeading(doc, el, 2, options, imagePaths, warnings, bookmarks, settings);
            case "h3" -> addHeading(doc, el, 3, options, imagePaths, warnings, bookmarks, settings);
            case "h4" -> addHeading(doc, el, 4, options, imagePaths, warnings, bookmarks, settings);
            case "h5" -> addHeading(doc, el, 5, options, imagePaths, warnings, bookmarks, settings);
            case "h6" -> addHeading(doc, el, 6, options, imagePaths, warnings, bookmarks, settings);
            case "p"  -> {
                XWPFParagraph p = doc.createParagraph();
                p.setSpacingBetween(settings.getLineHeight());
                p.setSpacingAfter(Twips.SPACE_PARA_AFTER);
                formatBlockParagraph(p, el);
                processInlineNodes(doc, p, el.childNodes(), options, imagePaths, warnings, new RunState(settings, bookmarks, el));
            }
            case "a", "span" -> {
                XWPFParagraph p = doc.createParagraph();
                formatBlockParagraph(p, el);
                processInlineElement(doc, p, el, options, imagePaths, warnings, new RunState(settings, bookmarks, el));
            }
            case "pre" -> addCodeBlock(doc, el, settings, bookmarks);
            case "ul" -> addList(doc, el, false, 1, options, imagePaths, warnings, settings, bookmarks);
            case "ol" -> addList(doc, el, true, 1, options, imagePaths, warnings, settings, bookmarks);
            case "blockquote" -> addBlockquote(doc, el, options, imagePaths, warnings, settings, bookmarks);
            case "table" -> addTable(doc, el, options, imagePaths, warnings, settings, bookmarks);
            case "hr" -> addHorizontalLine(doc, el);
            case "img" -> addImageParagraph(doc, el, options, imagePaths, warnings, new RunState(settings, bookmarks, el));
            default -> {
                if (!el.childNodes().isEmpty()) {
                    boolean isToc = el.hasClass("toc");
                    if (isToc) {
                        addSpacerParagraph(doc, Twips.SPACE_TOC_BEFORE);
                        addTocTitle(doc);
                    }
                    processBlockNodes(doc, el.childNodes(), options, imagePaths, warnings, bookmarks, settings);
                    if (isToc) addSpacerParagraph(doc, Twips.SPACE_TOC_AFTER);
                }
            }
        }
        bookmarks.markBlock(doc, el, firstBodyElement);
    }

    // ── 인라인 요소 처리 ──────────────────────────────────────────────────────

    private void processInlineNodes(XWPFDocument doc, XWPFParagraph para, List<Node> nodes,
                                    ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings,
                                    RunState state) {
        for (Node node : nodes) {
            if (node instanceof TextNode tn) {
                String text = tn.getWholeText();
                if (tn.nextSibling() instanceof Element next && next.isBlock()) text = text.stripTrailing();
                if (tn.previousSibling() instanceof Element previous && previous.isBlock()) text = text.stripLeading();
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
        state.bookmarks.mark(para, el, state.hyperlink);
        switch (el.tagName()) {
            case "strong", "b" ->
                processInlineNodes(doc, para, el.childNodes(), options, imagePaths, warnings, state.withBold(true));
            case "em", "i" ->
                processInlineNodes(doc, para, el.childNodes(), options, imagePaths, warnings, state.withItalic(true));
            case "del", "s" ->
                processInlineNodes(doc, para, el.childNodes(), options, imagePaths, warnings, state.withStrike(true));
            case "code" ->
                processInlineNodes(doc, para, el.childNodes(), options, imagePaths, warnings, state.withCode(true));
            case "a" -> addHyperlink(doc, para, el, options, imagePaths, warnings, state);
            case "br" -> {
                XWPFRun run = createRun(para, state);
                run.addBreak();
            }
            case "img" -> addInlineImage(para, el, options, imagePaths, warnings, state);
            default ->
                processInlineNodes(doc, para, el.childNodes(), options, imagePaths, warnings, state);
        }
    }

    private void applyRun(XWPFParagraph para, String text, RunState state) {
        XWPFRun run = createRun(para, state);
        run.setText(text);
    }

    private XWPFRun createRun(XWPFParagraph para, RunState state) {
        XWPFRun run = state.hyperlink == null ? para.createRun()
            : new XWPFHyperlinkRun(state.hyperlink, state.hyperlink.addNewR(), para);
        configureRun(run, state);
        if (state.hyperlink != null) {
            run.setColor("0563C1");
            run.setUnderline(UnderlinePatterns.SINGLE);
        }
        return run;
    }

    private void configureRun(XWPFRun run, RunState state) {
        UserSettings s = state.settings;
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
                            LocalImagePaths imagePaths, List<ConvertWarning> warnings, Bookmarks bookmarks, UserSettings settings) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + level);
        p.setSpacingBefore(Twips.SPACE_H_BEFORE);
        p.setSpacingAfter(Twips.SPACE_H_AFTER);
        p.setSpacingBetween(settings.getLineHeight());
        formatBlockParagraph(p, el);

        int size = headingSize(level, settings);
        RunState state = new RunState(settings, bookmarks, el);
        state.headingSize = size;
        processInlineNodes(doc, p, el.childNodes(), options, imagePaths, warnings, state);
    }

    // ── 코드 블록 ─────────────────────────────────────────────────────────────

    private void addCodeBlock(XWPFDocument doc, Element preEl, UserSettings settings, Bookmarks bookmarks) {
        String code = preEl.wholeText().replace("\r\n", "\n").replace('\r', '\n');
        // A final newline terminates the last code line, not an additional blank paragraph.
        if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
        boolean first = true;
        for (String line : code.split("\n", -1)) {
            XWPFParagraph p = doc.createParagraph();
            if (first) {
                for (Element target : preEl.select("[id], a[name]")) bookmarks.mark(p, target, null);
                first = false;
            }
            p.setIndentationLeft(Twips.INDENT_BLOCK);
            formatBlockParagraph(p, preEl);

            CTPPr pPr = p.getCTP().getPPr() != null ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
            CTShd shd = pPr.addNewShd();
            shd.setFill("F5F5F5");
            shd.setVal(STShd.CLEAR);

            XWPFRun run = createRun(p, new RunState(settings, bookmarks, preEl).withCode(true));
            String[] parts = line.split("\t", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) run.addTab();
                run.setText(parts[i]);
            }
        }
    }

    // ── 목록 ──────────────────────────────────────────────────────────────────

    private void addList(XWPFDocument doc, Element listEl, boolean ordered, int depth,
                         ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings, UserSettings settings, Bookmarks bookmarks) {
        int firstBodyElement = doc.getBodyElements().size();
        if (depth > 9) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DOCX 목록은 최대 9단계까지 지원합니다.");
        BigInteger start = ordered && listEl.hasAttr("start")
            ? new BigInteger(listEl.attr("start")) : BigInteger.ONE;
        BigInteger numId = createListNumbering(doc, ordered, depth, start);
        for (Element li : listEl.select("> li")) {
            XWPFParagraph p = listParagraph(doc, depth, numId, settings, li);
            bookmarks.mark(p, li, null);
            for (Node child : li.childNodes()) {
                if (child instanceof Element e && (e.tagName().equals("ul") || e.tagName().equals("ol"))) {
                    addList(doc, e, e.tagName().equals("ol"), depth + 1, options, imagePaths, warnings, settings, bookmarks);
                    p = null;
                } else if (child instanceof Element e && e.tagName().equals("p")) {
                    if (p == null || hasInlineContent(p)) p = listParagraph(doc, depth, null, settings, e);
                    bookmarks.mark(p, e, null);
                    processInlineNodes(doc, p, e.childNodes(), options, imagePaths, warnings, new RunState(settings, bookmarks, e));
                    p = null;
                } else if (child instanceof Element e && e.isBlock()) {
                    processBlockElement(doc, e, options, imagePaths, warnings, bookmarks, settings);
                    p = null;
                } else {
                    if (child instanceof TextNode text && text.isBlank()
                            && (p == null || !hasInlineContent(p)
                                || child.nextSibling() instanceof Element next && next.isBlock())) continue;
                    if (p == null) p = listParagraph(doc, depth, null, settings, li);
                    processInlineNodes(doc, p, List.of(child), options, imagePaths, warnings, new RunState(settings, bookmarks, li));
                }
            }
        }
        bookmarks.markBlock(doc, listEl, firstBodyElement);
    }

    private boolean hasInlineContent(XWPFParagraph paragraph) {
        return paragraph.getCTP().sizeOfRArray() > 0 || paragraph.getCTP().sizeOfHyperlinkArray() > 0;
    }

    private XWPFParagraph listParagraph(XWPFDocument doc, int depth, BigInteger numId, UserSettings settings, Node source) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("ListParagraph");
        p.setIndentationLeft(Twips.INDENT_BLOCK * depth);
        p.setSpacingBetween(settings.getLineHeight());
        p.setSpacingAfter(Twips.SPACE_LIST_AFTER);
        if (numId != null) {
            p.setNumID(numId);
            p.setNumILvl(BigInteger.valueOf(depth - 1));
            p.setIndentationHanging(Twips.INDENT_BLOCK / 2);
        }
        formatBlockParagraph(p, source);
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
                               ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings, UserSettings settings, Bookmarks bookmarks) {
        List<Node> inline = new ArrayList<>();
        for (Node child : el.childNodes()) {
            if (child instanceof Element block && block.isBlock()) {
                addQuotedInline(doc, el, inline, options, imagePaths, warnings, settings, bookmarks);
                inline.clear();
                processBlockElement(doc, block, options, imagePaths, warnings, bookmarks, settings);
            } else {
                inline.add(child);
            }
        }
        addQuotedInline(doc, el, inline, options, imagePaths, warnings, settings, bookmarks);
    }

    private void addQuotedInline(XWPFDocument doc, Element source, List<Node> nodes,
                                 ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings,
                                 UserSettings settings, Bookmarks bookmarks) {
        if (nodes.stream().noneMatch(node -> node instanceof Element
                || node instanceof TextNode text && !text.isBlank())) return;
        XWPFParagraph p = doc.createParagraph();
        formatBlockParagraph(p, source);
        processInlineNodes(doc, p, nodes, options, imagePaths, warnings, new RunState(settings, bookmarks, source));
    }

    private static int quoteDepth(Node source) {
        int depth = 0;
        for (Node node = source; node != null; node = node.parent()) {
            if (node instanceof Element element && element.tagName().equals("blockquote")) depth++;
        }
        return depth;
    }

    private void formatBlockParagraph(XWPFParagraph paragraph, Node source) {
        int quotes = quoteDepth(source);
        int indent = quotes;
        if (!"ListParagraph".equals(paragraph.getStyleID())) {
            for (Node node = source; node != null; node = node.parent()) {
                if (node instanceof Element element && element.tagName().equals("li")) indent++;
            }
        }
        if (indent > 0) {
            paragraph.setIndentationLeft(Math.max(0, paragraph.getIndentationLeft()) + Twips.INDENT_BLOCK * indent);
        }
        if (quotes > 0) {
            CTPPr properties = paragraph.getCTP().isSetPPr()
                ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
            CTBorder left = (properties.isSetPBdr() ? properties.getPBdr() : properties.addNewPBdr()).addNewLeft();
            left.setVal(STBorder.SINGLE);
            left.setSz(BigInteger.valueOf(Twips.BORDER_QUOTE_SZ));
            left.setColor("CCCCCC");
        }
    }

    // ── 표 ────────────────────────────────────────────────────────────────────

    private void addTable(XWPFDocument doc, Element tableEl,
                          ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings, UserSettings settings, Bookmarks bookmarks) {
        var rows = tableEl.select("tr");
        if (rows.isEmpty()) return;

        int cols = rows.get(0).select("th, td").size();
        if (cols == 0) return;

        XWPFTable table = doc.createTable(rows.size(), cols);
        table.setWidth("100%");
        table.setCellMargins(0, Twips.CELL_MARGIN, 0, Twips.CELL_MARGIN);
        int columnWidth = (int) (contentWidthTwips(doc) / cols);

        for (int r = 0; r < rows.size(); r++) {
            var cells = rows.get(r).select("th, td");
            XWPFTableRow row = table.getRow(r);
            boolean isHeader = !rows.get(r).select("th").isEmpty();

            for (int c = 0; c < cells.size() && c < cols; c++) {
                XWPFTableCell cell = row.getCell(c);
                if (cell == null) cell = row.addNewTableCell();
                cell.setWidth(Integer.toString(columnWidth));

                XWPFParagraph p = cell.getParagraphs().get(0);
                formatBlockParagraph(p, cells.get(c));
                bookmarks.mark(p, cells.get(c), null);
                for (Element parent : cells.get(c).parents()) {
                    if (parent == tableEl) break;
                    bookmarks.mark(p, parent, null);
                }
                RunState state = new RunState(settings, bookmarks, cells.get(c));
                if (isHeader) state = state.withBold(true);
                processInlineNodes(doc, p, cells.get(c).childNodes(), options, imagePaths, warnings, state);

                if (isHeader) {
                    CTShd shd = cell.getCTTc().getTcPr().addNewShd();
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

    private void addHorizontalLine(XWPFDocument doc, Element source) {
        XWPFParagraph p = doc.createParagraph();
        formatBlockParagraph(p, source);
        CTPPr properties = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTBorder bottom = (properties.isSetPBdr() ? properties.getPBdr() : properties.addNewPBdr()).addNewBottom();
        bottom.setVal(STBorder.SINGLE);
        bottom.setSz(BigInteger.valueOf(Twips.BORDER_HR_SZ));
        bottom.setColor("CCCCCC");
    }

    // ── 이미지 (블록) ────────────────────────────────────────────────────────

    private void addImageParagraph(XWPFDocument doc, Element imgEl,
                                   ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings, RunState state) {
        if (!options.isIncludeImages()) return;
        String src = imgEl.attr("src");
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        formatBlockParagraph(p, imgEl);
        embedImage(p, src, options, imagePaths, warnings, state);
    }

    private void addInlineImage(XWPFParagraph para, Element imgEl,
                                ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings, RunState state) {
        if (!options.isIncludeImages()) return;
        String src = imgEl.attr("src");
        embedImage(para, src, options, imagePaths, warnings, state);
    }

    private void embedImage(XWPFParagraph para, String src,
                            ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings, RunState state) {
        ResolvedImage image = imageResolver.resolve(src, imagePaths, warnings, options.getRemoteImageTimeout());
        if (image == null) {
            applyRun(para, "[이미지 로드 실패: " + ConvertWarning.imageTarget(src) + "]", state);
            return;
        }
        try {
            int[] emu = calcImageEmu(image, para);
            XWPFRun run = createRun(para, state);
            run.addPicture(new ByteArrayInputStream(image.bytes()), image.pictureType(),
                ConvertWarning.imageTarget(src), emu[0], emu[1]);
        } catch (IOException | InvalidFormatException | IllegalArgumentException e) {
            warnings.add(ConvertWarning.imageEmbedFailed(src));
            applyRun(para, "[이미지 로드 실패: " + ConvertWarning.imageTarget(src) + "]", state);
        }
    }

    private int[] calcImageEmu(ResolvedImage image, XWPFParagraph paragraph) {
        XWPFDocument doc = paragraph.getDocument();
        double widthTwips = contentWidthTwips(doc);
        if (paragraph.getBody() instanceof XWPFTableCell cell) {
            widthTwips = Math.min(widthTwips, cell.getWidth() - 2.0 * Twips.CELL_MARGIN);
        }
        widthTwips -= Math.max(0, paragraph.getIndentationLeft()) + Math.max(0, paragraph.getIndentationRight());
        CTSectPr section = doc.getDocument().getBody().getSectPr();
        double heightTwips = Double.parseDouble(section.getPgSz().getH().toString())
            - Double.parseDouble(section.getPgMar().getTop().toString())
            - Double.parseDouble(section.getPgMar().getBottom().toString());
        heightTwips -= Math.max(0, paragraph.getSpacingBefore()) + Math.max(0, paragraph.getSpacingAfter());
        if (widthTwips <= 0 || heightTwips <= 0) {
            throw new IllegalArgumentException("No page or cell area is available for the image");
        }
        double wPt = image.width() * IMG_PX_TO_PT;
        double hPt = image.height() * IMG_PX_TO_PT;
        double scale = Math.min(1.0, Math.min(Math.min(IMG_MAX_WIDTH_PT, widthTwips / 20) / wPt,
            Math.min(IMG_MAX_HEIGHT_PT, heightTwips / 20) / hPt));
        return new int[]{Math.max(1, Units.toEMU(wPt * scale)), Math.max(1, Units.toEMU(hPt * scale))};
    }

    private double contentWidthTwips(XWPFDocument doc) {
        CTSectPr section = doc.getDocument().getBody().getSectPr();
        return Double.parseDouble(section.getPgSz().getW().toString())
            - Double.parseDouble(section.getPgMar().getLeft().toString())
            - Double.parseDouble(section.getPgMar().getRight().toString());
    }

    // ── 하이퍼링크 ────────────────────────────────────────────────────────────

    private void addHyperlink(XWPFDocument doc, XWPFParagraph para, Element aEl,
                              ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings, RunState state) {
        if ("ignore".equals(options.getLinkStrategy()) || !aEl.hasAttr("href")) {
            processInlineNodes(doc, para, aEl.childNodes(), options, imagePaths, warnings, state);
            return;
        }
        String href = aEl.attr("href").strip();
        URI uri = ResourceUri.parse(href);
        if (!ResourceUri.isAllowedLink(uri)) {
            warnings.add(ConvertWarning.unsupportedResource(href));
            processInlineNodes(doc, para, aEl.childNodes(), options, imagePaths, warnings, state);
            return;
        }
        String anchor = href.startsWith("#") ? state.bookmarks.name(uri.getFragment()) : null;
        if (href.startsWith("#") && anchor == null) {
            warnings.add(ConvertWarning.hyperlinkUnresolved(href));
            processInlineNodes(doc, para, aEl.childNodes(), options, imagePaths, warnings, state);
        } else if ("warn".equals(options.getLinkStrategy()) && uri.getScheme() == null && !href.startsWith("#")) {
            warnings.add(ConvertWarning.attachmentNotFound(href));
            processInlineNodes(doc, para, aEl.childNodes(), options, imagePaths, warnings, state);
            applyRun(para, " [⚠ 첨부파일 미포함: " + href + "]", state);
        } else {
            CTHyperlink link = para.getCTP().addNewHyperlink();
            if (anchor != null) {
                link.setAnchor(anchor);
                state.bookmarks.track(link, para, uri.getFragment(), href, state.color);
            }
            else link.setId(doc.getPackagePart().addExternalRelationship(href, XWPFRelation.HYPERLINK.getRelation()).getId());
            processInlineNodes(doc, para, aEl.childNodes(), options, imagePaths, warnings, state.withHyperlink(link));
        }
    }

    // ── 기본 스타일 ──────────────────────────────────────────────────────────

    private void setupDefaultStyles(XWPFDocument doc, ConvertOptions options, UserSettings settings) {
        XWPFStyles styles = doc.createStyles();
        CTStyle normal = CTStyle.Factory.newInstance();
        normal.setStyleId("Normal");
        normal.setType(STStyleType.PARAGRAPH);
        normal.setDefault(true);
        normal.addNewName().setVal("Normal");
        CTSpacing spacing = normal.addNewPPr().addNewSpacing();
        spacing.setLine(BigInteger.valueOf(Math.round(settings.getLineHeight() * 240)));
        spacing.setLineRule(STLineSpacingRule.AUTO);
        CTRPr normalRun = normal.addNewRPr();
        normalRun.addNewSz().setVal(BigInteger.valueOf(settings.getBodyFontSizePt() * 2L));
        normalRun.addNewSzCs().setVal(BigInteger.valueOf(settings.getBodyFontSizePt() * 2L));
        CTFonts normalFonts = normalRun.addNewRFonts();
        String bodyFont = primaryFont(settings.getBodyFontFamily());
        normalFonts.setAscii(bodyFont);
        normalFonts.setHAnsi(bodyFont);
        normalFonts.setEastAsia(bodyFont);
        styles.addStyle(new XWPFStyle(normal));
        CTStyle list = CTStyle.Factory.newInstance();
        list.setStyleId("ListParagraph");
        list.setType(STStyleType.PARAGRAPH);
        list.addNewName().setVal("List Paragraph");
        list.addNewBasedOn().setVal("Normal");
        styles.addStyle(new XWPFStyle(list));
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

        CTPageMar pgMar = sectPr.addNewPgMar();
        pgMar.setTop(BigInteger.valueOf(mmToTwips(settings.getMarginTopMm())));
        pgMar.setBottom(BigInteger.valueOf(mmToTwips(settings.getMarginBottomMm())));
        pgMar.setLeft(BigInteger.valueOf(mmToTwips(settings.getMarginLeftMm())));
        pgMar.setRight(BigInteger.valueOf(mmToTwips(settings.getMarginRightMm())));
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
        static final int CELL_MARGIN       = 108;
    }

    private static class Bookmarks {
        private record Target(BigInteger id, String name) {}
        private record Reference(CTHyperlink link, XWPFParagraph paragraph, Target target, String href, String color) {}
        private final Map<String, Target> ids = new HashMap<>();
        private final Map<Element, List<Target>> elements = new IdentityHashMap<>();
        private final Set<Target> emitted = new HashSet<>();
        private final List<Reference> references = new ArrayList<>();

        Bookmarks(Element body) {
            var targets = body.select("[id], a[name]");
            Set<String> reserved = new HashSet<>();
            for (Element element : targets) {
                for (String id : List.of(element.id(), element.tagName().equals("a") ? element.attr("name") : "")) {
                    if (id.matches("[A-Za-z][A-Za-z0-9_]{0,39}")) reserved.add(id.toLowerCase(Locale.ROOT));
                }
            }
            Set<String> used = new HashSet<>();
            int nextName = 1;
            for (Element element : targets) {
                for (String id : List.of(element.id(), element.tagName().equals("a") ? element.attr("name") : "")) {
                    if (id.isEmpty() || ids.containsKey(id)) continue;
                    String name = id;
                    if (!id.matches("[A-Za-z][A-Za-z0-9_]{0,39}") || !used.add(id.toLowerCase(Locale.ROOT))) {
                        do { name = "md2docu_" + nextName++; }
                        while (reserved.contains(name.toLowerCase(Locale.ROOT)) || !used.add(name.toLowerCase(Locale.ROOT)));
                    }
                    Target target = new Target(BigInteger.valueOf(ids.size()), name);
                    ids.put(id, target);
                    elements.computeIfAbsent(element, key -> new ArrayList<>()).add(target);
                }
            }
        }

        String name(String id) {
            Target target = ids.get(id);
            return target == null ? null : target.name();
        }

        void track(CTHyperlink link, XWPFParagraph paragraph, String fragment, String href, String color) {
            references.add(new Reference(link, paragraph, ids.get(fragment), href, color));
        }

        void finish(List<ConvertWarning> warnings) {
            for (Reference reference : references) {
                if (emitted.contains(reference.target())) continue;
                warnings.add(ConvertWarning.hyperlinkUnresolved(reference.href()));
                for (CTR ctr : reference.link().getRList()) {
                    XWPFRun run = new XWPFRun(ctr, reference.paragraph());
                    run.setUnderline(UnderlinePatterns.NONE);
                    run.setColor(reference.color() == null ? "auto" : reference.color());
                }
                org.w3c.dom.Node link = reference.link().getDomNode();
                org.w3c.dom.Node parent = link.getParentNode();
                while (link.hasChildNodes()) parent.insertBefore(link.getFirstChild(), link);
                parent.removeChild(link);
            }
        }

        void mark(XWPFParagraph paragraph, Element element, CTHyperlink hyperlink) {
            for (Target target : elements.getOrDefault(element, List.of())) {
                if (!emitted.add(target)) continue;
                CTBookmark start = hyperlink == null ? paragraph.getCTP().addNewBookmarkStart() : hyperlink.addNewBookmarkStart();
                start.setId(target.id());
                start.setName(target.name());
                CTMarkupRange end = hyperlink == null ? paragraph.getCTP().addNewBookmarkEnd() : hyperlink.addNewBookmarkEnd();
                end.setId(target.id());
            }
        }

        void markBlock(XWPFDocument document, Element element, int firstBodyElement) {
            if (!elements.containsKey(element) || elements.get(element).stream().allMatch(emitted::contains)) return;
            for (int i = firstBodyElement; i < document.getBodyElements().size(); i++) {
                IBodyElement body = document.getBodyElements().get(i);
                if (body instanceof XWPFParagraph paragraph) {
                    mark(paragraph, element, null);
                    return;
                }
                if (body instanceof XWPFTable table && !table.getRows().isEmpty() && !table.getRow(0).getTableCells().isEmpty()) {
                    mark(table.getRow(0).getCell(0).getParagraphs().get(0), element, null);
                    return;
                }
            }
            mark(document.createParagraph(), element, null);
        }
    }

    // ── RunState (불변 포맷 상태) ─────────────────────────────────────────────

    private static class RunState {
        final UserSettings settings;
        final Bookmarks bookmarks;
        CTHyperlink hyperlink;
        boolean bold, italic, strike, code;
        String color;
        Integer headingSize;

        RunState(UserSettings settings, Bookmarks bookmarks) { this.settings = settings; this.bookmarks = bookmarks; }

        RunState(UserSettings settings, Bookmarks bookmarks, Node source) {
            this(settings, bookmarks);
            if (quoteDepth(source) > 0) color = "666666";
        }

        RunState withBold(boolean v)   { RunState s = copy(); s.bold = v; return s; }
        RunState withItalic(boolean v) { RunState s = copy(); s.italic = v; return s; }
        RunState withStrike(boolean v) { RunState s = copy(); s.strike = v; return s; }
        RunState withCode(boolean v)   { RunState s = copy(); s.code = v; return s; }
        RunState withColor(String c)   { RunState s = copy(); s.color = c; return s; }
        RunState withHyperlink(CTHyperlink link) { RunState s = copy(); s.hyperlink = link; return s; }

        private RunState copy() {
            RunState s = new RunState(settings, bookmarks);
            s.bold = bold; s.italic = italic; s.strike = strike;
            s.code = code; s.color = color;
            s.headingSize = headingSize;
            s.hyperlink = hyperlink;
            return s;
        }
    }
}
