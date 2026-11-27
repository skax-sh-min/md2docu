package com.md2docu.service;

import com.md2docu.model.ConvertWarning;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.jsoup.parser.Parser;
import org.w3c.dom.Node;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JRuntimeException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

import static com.md2docu.util.MarkdownText.escapeText;

@Service
public class DocxToMarkdownConverter {

    public record Output(String markdown, Map<String, byte[]> images) {}

    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final Set<String> INLINE_METADATA =
        Set.of("pPr", "rPr", "bookmarkStart", "bookmarkEnd", "proofErr", "sdtPr");
    private enum InlineKind { TEXT, IMAGE, BREAK, TAB }
    private record InlineStyle(boolean bold, boolean italic, boolean strike, boolean code) {
        boolean formatted() { return bold || italic || strike || code; }
    }
    private record InlinePart(InlineKind kind, String text, InlineStyle style, String href) {}

    private record ConvertContext(
        List<ConvertWarning> warnings,
        Map<String, byte[]> images,
        IdentityHashMap<XWPFPictureData, String> picNames,
        ListState lists
    ) {}

    private record NumberKey(BigInteger numId, int level) {}
    private record ListDefinition(boolean ordered, BigInteger start, int restartLevel) {}
    private record ListLevel(BigInteger numId, int level, int contentIndent, int wordIndent) {}
    private static class ListState {
        final Map<NumberKey, BigInteger> counters = new HashMap<>();
        final Map<NumberKey, Integer> restartLevels = new HashMap<>();
        final TreeMap<Integer, ListLevel> active = new TreeMap<>();
    }

    public Output convert(byte[] docxBytes, List<ConvertWarning> warnings) throws IOException {
        ConvertContext ctx = new ConvertContext(warnings, new LinkedHashMap<>(), new IdentityHashMap<>(),
            new ListState());
        try (XWPFDocument doc = openDocument(docxBytes)) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph para) {
                    appendParagraph(doc, para, sb, ctx);
                } else if (el instanceof XWPFTable table) {
                    endList(sb, ctx);
                    appendTable(table, sb, ctx);
                } else {
                    unsupported(ctx, el.getElementType().toString());
                    if (el instanceof XWPFSDT control) {
                        endList(sb, ctx);
                        sb.append(escapeText(control.getContent().getText())).append("\n\n");
                    }

                }
            }
            return new Output(sb.toString().stripTrailing() + "\n", ctx.images());
        }
    }

    private XWPFDocument openDocument(byte[] bytes) {
        try {
            return new XWPFDocument(new ByteArrayInputStream(bytes));
        } catch (IOException | POIXMLException | OpenXML4JRuntimeException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DOCX 파일이 손상되었거나 올바른 Word 문서가 아닙니다.", e);
        }
    }

    private void appendParagraph(XWPFDocument doc, XWPFParagraph para,
                                  StringBuilder sb, ConvertContext ctx) {
        int heading = headingLevel(doc, para);
        String text = paragraphText(para, ctx, heading > 0);

        if (heading > 0) {
            endList(sb, ctx);
            if (!text.isBlank()) {
                sb.append("#".repeat(heading)).append(" ").append(text.trim()).append("\n\n");
            }
            return;
        }

        if (para.getNumID() != null) {
            appendListParagraph(doc, para, text, sb, ctx);
            return;
        }

        if ("ListParagraph".equals(para.getStyleID()) && !text.isBlank()) {
            ListLevel level = ctx.lists().active.descendingMap().values().stream()
                .filter(item -> item.wordIndent() == para.getIndentationLeft()).findFirst().orElse(null);
            if (level != null) {
                ctx.lists().active.tailMap(level.level() + 1).clear();
                String indent = " ".repeat(level.contentIndent());
                sb.append("\n").append(indent).append(text.replace("\n", "\n" + indent)).append("\n\n");
                return;
            }
        }
        if (text.isBlank()) {
            sb.append("\n");
            return;
        }
        endList(sb, ctx);
        sb.append(text).append("\n\n");
    }

    private void endList(StringBuilder sb, ConvertContext ctx) {
        if (!ctx.lists().active.isEmpty()) sb.append("\n");
        ctx.lists().active.clear();
    }

    private void appendListParagraph(XWPFDocument doc, XWPFParagraph para, String text,
                                     StringBuilder sb, ConvertContext ctx) {
        int depth = para.getNumIlvl() != null ? para.getNumIlvl().intValue() : 0;
        ListDefinition definition = listDefinition(doc, para, depth, ctx);
        if (definition == null) {
            endList(sb, ctx);
            sb.append(text).append("\n\n");
            return;
        }
        ListState lists = ctx.lists();
        NumberKey key = new NumberKey(para.getNumID(), depth);
        BigInteger value = lists.counters.getOrDefault(key, definition.start().subtract(BigInteger.ONE))
            .add(BigInteger.ONE);
        lists.counters.put(key, value);
        lists.restartLevels.put(key, definition.restartLevel());
        lists.counters.keySet().removeIf(k -> k.numId().equals(key.numId()) && k.level() > depth
            && lists.restartLevels.get(k) >= depth);
        ListLevel previous = lists.active.get(depth);
        lists.active.tailMap(depth + 1).clear();
        var parent = lists.active.lowerEntry(depth);
        int indent = parent == null ? 0 : parent.getValue().contentIndent();
        if (previous != null && !previous.numId().equals(para.getNumID())) {
            sb.append("\n").append(" ".repeat(indent)).append("<!-- list break -->\n\n");
        }
        boolean continuing = previous != null && previous.numId().equals(para.getNumID());
        if (definition.ordered() && !continuing && (value.signum() < 0 || value.toString().length() > 9)) {
            ctx.warnings().add(new ConvertWarning("UNSUPPORTED_LIST_START", value.toString(),
                "Markdown으로 표현할 수 없는 목록 시작 번호를 1로 변환했습니다: " + value));
            value = BigInteger.ONE;
        }
        String marker = definition.ordered() ? (continuing ? "1." : value + ".") : "-";
        int contentIndent = indent + marker.length() + 1;
        sb.append(" ".repeat(indent)).append(marker).append(" ")
            .append(text.replace("\n", "\n" + " ".repeat(contentIndent))).append("\n");
        lists.active.put(depth, new ListLevel(para.getNumID(), depth, contentIndent,
            para.getIndentationLeft()));
    }

    private String paragraphText(XWPFParagraph para, ConvertContext ctx, boolean singleLine) {
        List<InlinePart> parts = new ArrayList<>();
        collectInline(para.getCTP(), para, null, parts, ctx);
        return renderInline(parts, singleLine);
    }

    private void collectInline(XmlObject container, XWPFParagraph para, String href,
                                List<InlinePart> parts, ConvertContext ctx) {
        try (XmlCursor cursor = container.newCursor()) {
            if (!cursor.toFirstChild()) return;
            do {
                XmlObject child = cursor.getObject();
                if (child instanceof CTR run) {
                    collectRun(new XWPFRun(run, para), href, parts, ctx);
                } else if (child instanceof CTHyperlink link) {
                    String target = resolveHyperlinkUrl(para.getDocument(), link);
                    if (target == null) {
                        ctx.warnings().add(new ConvertWarning("HYPERLINK_UNRESOLVED", para.getText(),
                            "하이퍼링크 URL을 확인할 수 없어 표시 내용을 보존했습니다."));
                    } else if (link.isSetAnchor()) {
                        unsupported(ctx, "bookmark-target:" + link.getAnchor());
                    }
                    collectInline(child, para, target, parts, ctx);
                } else {
                    String name = cursor.getName().getLocalPart();
                    if (INLINE_METADATA.contains(name)) continue;
                    unsupported(ctx, name);
                    collectInline(child, para, href, parts, ctx);
                }
            } while (cursor.toNextSibling());
        }
    }

    private void collectRun(XWPFRun run, String href, List<InlinePart> parts, ConvertContext ctx) {
        InlineStyle style = new InlineStyle(run.isBold(), run.isItalic(), run.isStrikeThrough(),
            "Courier New".equalsIgnoreCase(run.getFontFamily()));
        for (Node node = run.getCTR().getDomNode().getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = node.getLocalName();
            switch (name) {
                case "rPr" -> { }
                case "t" -> addText(parts, nodeText(node), style, href);
                case "tab" -> parts.add(new InlinePart(InlineKind.TAB, "", style, href));
                case "br", "cr" -> {
                    String type = attribute(node, WORD_NS, "type");
                    if (!type.isEmpty() && !"textWrapping".equals(type)) unsupported(ctx, "br:" + type);
                    parts.add(new InlinePart(InlineKind.BREAK, "", style, href));
                }
                case "noBreakHyphen" -> addText(parts, "\u2011", style, href);
                case "softHyphen" -> addText(parts, "\u00ad", style, href);
                case "drawing", "pict" -> {
                    if (!collectImages(node, run.getDocument(), style, href, parts, ctx)) unsupported(ctx, name);
                }
                default -> unsupported(ctx, name);
            }
        }
    }

    private boolean collectImages(Node node, XWPFDocument doc, InlineStyle style, String href,
                                   List<InlinePart> parts, ConvertContext ctx) {
        boolean found = false;
        if ("blip".equals(node.getLocalName()) || "imagedata".equals(node.getLocalName())) {
            String id = attribute(node, REL_NS, "embed");
            if (id.isEmpty()) id = attribute(node, REL_NS, "id");
            XWPFPictureData data = id.isEmpty() ? null : doc.getPictureDataByID(id);
            if (data == null) {
                ctx.warnings().add(new ConvertWarning("IMAGE_NOT_FOUND", id,
                    "DOCX 이미지 데이터를 확인할 수 없습니다: " + id));
            } else {
                parts.add(new InlinePart(InlineKind.IMAGE, resolveImageFilename(data, ctx), style, href));
            }
            found = true;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            found |= collectImages(child, doc, style, href, parts, ctx);
        }
        return found;
    }

    private String attribute(Node node, String namespace, String name) {
        Node attr = node.getAttributes() == null ? null : node.getAttributes().getNamedItemNS(namespace, name);
        return attr == null ? "" : attr.getNodeValue();
    }

    private String nodeText(Node node) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            return node.getNodeValue();
        }
        StringBuilder text = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            text.append(nodeText(child));
        }
        return text.toString();
    }

    private void unsupported(ConvertContext ctx, String feature) {
        ctx.warnings().add(new ConvertWarning("UNSUPPORTED_DOCX_CONTENT", feature,
            "지원하지 않는 DOCX 구조입니다. 가능한 표시 내용만 보존했습니다: " + feature));
    }

    private void addText(List<InlinePart> parts, String text, InlineStyle style, String href) {
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                addTextPart(parts, chunk.toString(), style, href);
                chunk.setLength(0);
                parts.add(new InlinePart(c == '\t' ? InlineKind.TAB : InlineKind.BREAK, "", style, href));
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            } else {
                chunk.append(c);
            }
        }
        addTextPart(parts, chunk.toString(), style, href);
    }

    private void addTextPart(List<InlinePart> parts, String text, InlineStyle style, String href) {
        if (text.isEmpty()) return;
        if (!parts.isEmpty()) {
            InlinePart previous = parts.get(parts.size() - 1);
            if (previous.kind() == InlineKind.TEXT && previous.style().equals(style)
                    && Objects.equals(previous.href(), href)) {
                parts.set(parts.size() - 1, new InlinePart(InlineKind.TEXT, previous.text() + text, style, href));
                return;
            }
        }
        parts.add(new InlinePart(InlineKind.TEXT, text, style, href));
    }

    private String renderInline(List<InlinePart> parts, boolean singleLine) {
        boolean mixedStyles = parts.stream()
            .filter(part -> part.kind() == InlineKind.TEXT && part.style().formatted()).count() > 1;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            InlinePart part = parts.get(i);
            String value = switch (part.kind()) {
                case TEXT -> styledText(part.text(), part.style(), singleLine, mixedStyles);
                case IMAGE -> "![이미지](<" + escapeDestination(part.text()) + ">)";
                case TAB -> "&#9;";
                case BREAK -> !singleLine && i > 0 && i + 1 < parts.size()
                    && parts.get(i - 1).kind() != InlineKind.BREAK
                    && parts.get(i + 1).kind() != InlineKind.BREAK ? "  \n" : "<br />";
            };
            if (part.href() != null && part.kind() != InlineKind.BREAK && part.kind() != InlineKind.TAB) {
                // HTML anchors avoid double entity decoding in Markdown link destinations.
                if (!Parser.unescapeEntities(part.href(), false).equals(part.href())) {
                    value = "<a href=\"" + htmlAttribute(part.href()) + "\">" + value + "</a>";
                } else {
                    value = "[" + value + "](<" + escapeDestination(part.href()) + ">)";
                }
            }
            result.append(value);
        }
        return result.toString();
    }

    private String styledText(String text, InlineStyle style, boolean singleLine, boolean mixedStyles) {
        if (style.code()) {
            if (singleLine || text.startsWith(" ") || text.endsWith(" ")) {
                return wrapHtml("<code>" + literalHtml(text) + "</code>", style);
            }
            int longest = 0, current = 0;
            for (char c : text.toCharArray()) {
                current = c == '`' ? current + 1 : 0;
                longest = Math.max(longest, current);
            }
            String fence = "`".repeat(longest + 1);
            boolean pad = text.startsWith("`") || text.endsWith("`");
            return wrapHtml(fence + (pad ? " " : "") + text + (pad ? " " : "") + fence, style);
        }
        if (!style.formatted() || text.isBlank()) return escapeText(text);
        int start = 0, end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        String core = text.substring(start, end);
        String value = escapeText(core);
        boolean html = mixedStyles || !Character.isLetterOrDigit(core.charAt(0))
            || !Character.isLetterOrDigit(core.charAt(core.length() - 1));
        if (html) {
            value = wrapHtml(value, style);
        } else {
            String marker = style.bold() && style.italic() ? "***" : style.bold() ? "**" : style.italic() ? "*" : "";
            value = marker + value + marker;
            if (style.strike()) value = "~~" + value + "~~";
        }
        return escapeText(text.substring(0, start)) + value + escapeText(text.substring(end));
    }

    private String wrapHtml(String value, InlineStyle style) {
        if (style.italic()) value = "<em>" + value + "</em>";
        if (style.bold()) value = "<strong>" + value + "</strong>";
        if (style.strike()) value = "<del>" + value + "</del>";
        return value;
    }

    private String literalHtml(String text) {
        StringBuilder html = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c < 128 && !Character.isLetterOrDigit(c)) html.append("&#").append((int) c).append(';');
            else html.append(c);
        }
        return html.toString();
    }

    private String escapeDestination(String destination) {
        return destination.replace("&", "&amp;").replace("<", "%3C").replace(">", "%3E")
            .replace("\\", "%5C").replace("|", "%7C").replace(" ", "%20").replace("\t", "%09")
            .replace("\r", "%0D").replace("\n", "%0A");
    }

    private String htmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("|", "%7C").replace("\r", "%0D").replace("\n", "%0A");
    }

    private String resolveImageFilename(XWPFPictureData picData, ConvertContext ctx) {
        return ctx.picNames().computeIfAbsent(picData, pd -> {
            String ext = pd.suggestFileExtension();
            String name = "images/image_" + (ctx.images().size() + 1)
                        + (ext.isEmpty() ? "" : "." + ext);
            ctx.images().put(name, pd.getData());
            return name;
        });
    }

    private String resolveHyperlinkUrl(XWPFDocument doc, CTHyperlink link) {
        if (link.isSetAnchor() && !link.getAnchor().isEmpty()) return "#" + link.getAnchor();
        if (!link.isSetId()) return null;
        var relationship = doc.getPackagePart().getRelationship(link.getId());
        return relationship == null ? null : relationship.getTargetURI().toASCIIString();
    }

    private int headingLevel(XWPFDocument doc, XWPFParagraph para) {
        String styleId = para.getStyleID();
        if (styleId == null) return 0;

        // styleId는 로케일과 무관하게 안정적 (e.g. Heading1..Heading6)
        if (styleId.matches("(?i)Heading[1-6]")) {
            return styleId.charAt(styleId.length() - 1) - '0';
        }

        // 한국어 등 비영어 설치에서는 표시 이름이 로컬라이즈됨 (e.g. "제목 1")
        XWPFStyles styles = doc.getStyles();
        if (styles == null) return 0;
        XWPFStyle style = styles.getStyle(styleId);
        if (style == null) return 0;
        String name = style.getName();
        if (name == null) return 0;
        name = name.toLowerCase().trim();
        if (name.startsWith("heading ")) {
            try {
                return Math.min(Integer.parseInt(name.substring(8).trim()), 6);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private ListDefinition listDefinition(XWPFDocument doc, XWPFParagraph para, int depth,
                                           ConvertContext ctx) {
        XWPFNumbering numbering = doc.getNumbering();
        XWPFNum num = numbering == null ? null : numbering.getNum(para.getNumID());
        XWPFAbstractNum abstractNum = num == null || num.getCTNum().getAbstractNumId() == null
            ? null : numbering.getAbstractNum(num.getCTNum().getAbstractNumId().getVal());
        CTLvl lvl = abstractNum == null ? null : abstractNum.getCTAbstractNum().getLvlList().stream()
            .filter(level -> BigInteger.valueOf(depth).equals(level.getIlvl())).findFirst().orElse(null);
        BigInteger startOverride = null;
        if (num != null) {
            for (var override : num.getCTNum().getLvlOverrideList()) {
                if (BigInteger.valueOf(depth).equals(override.getIlvl())) {
                    if (override.isSetLvl()) lvl = override.getLvl();
                    if (override.isSetStartOverride()) startOverride = override.getStartOverride().getVal();
                }
            }
        }
        if (depth < 0 || depth > 8 || lvl == null || lvl.getNumFmt() == null
                || lvl.getNumFmt().getVal() == null) {
            ctx.warnings().add(new ConvertWarning("LIST_NUMBERING_UNRESOLVED", para.getText(),
                "목록 번호 정의를 확인할 수 없어 일반 문단으로 변환했습니다."));
            return null;
        }
        String format = lvl.getNumFmt().getVal().toString();
        if (!"bullet".equals(format) && !"decimal".equals(format)) {
            ctx.warnings().add(new ConvertWarning("UNSUPPORTED_LIST_FORMAT", format,
                "지원하지 않는 목록 번호 형식을 십진수로 변환했습니다: " + format));
        }
        BigInteger start = startOverride != null ? startOverride
            : lvl.getStart() == null ? BigInteger.ONE : lvl.getStart().getVal();
        int restartLevel = depth - 1;
        if (lvl.getLvlRestart() != null) {
            int restart = lvl.getLvlRestart().getVal().intValue();
            if (restart >= 0 && restart <= depth) restartLevel = restart - 1;
        }
        return new ListDefinition(!"bullet".equals(format), start, restartLevel);
    }

    private void appendTable(XWPFTable table, StringBuilder sb, ConvertContext ctx) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return;
        int columns = rows.stream().mapToInt(row -> row.getTableCells().size()).max().orElse(0);

        sb.append("\n");
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            sb.append("|");
            for (XWPFTableCell cell : row.getTableCells()) {
                if (cell.getCTTc().getTcPr() != null && (cell.getCTTc().getTcPr().isSetGridSpan()
                        || cell.getCTTc().getTcPr().isSetVMerge())) unsupported(ctx, "merged-table-cell");
                List<String> paragraphs = new ArrayList<>();
                for (IBodyElement element : cell.getBodyElements()) {
                    if (element instanceof XWPFParagraph para) paragraphs.add(paragraphText(para, ctx, true));
                    else if (element instanceof XWPFTable nested) {
                        unsupported(ctx, "nested-table");
                        paragraphs.add(escapeText(nested.getText()));
                    } else unsupported(ctx, element.getElementType().toString());
                }
                String text = String.join("<br />", paragraphs);
                sb.append(" ").append(text).append(" |");
            }
            for (int missing = row.getTableCells().size(); missing < columns; missing++) sb.append("  |");
            sb.append("\n");
            if (i == 0) {
                sb.append("|");
                for (int j = 0; j < columns; j++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
    }
}
