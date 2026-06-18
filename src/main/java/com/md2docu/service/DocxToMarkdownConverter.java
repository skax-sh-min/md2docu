package com.md2docu.service;

import com.md2docu.model.ConvertWarning;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DocxToMarkdownConverter {

    public record Output(String markdown, Map<String, byte[]> images) {}

    private record ConvertContext(
        List<ConvertWarning> warnings,
        Map<String, byte[]> images,
        IdentityHashMap<XWPFPictureData, String> picNames
    ) {}

    public Output convert(byte[] docxBytes, List<ConvertWarning> warnings) throws IOException {
        ConvertContext ctx = new ConvertContext(warnings, new LinkedHashMap<>(), new IdentityHashMap<>());
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph para) {
                    appendParagraph(doc, para, sb, ctx);
                } else if (el instanceof XWPFTable table) {
                    appendTable(table, sb, ctx);
                }
            }
            return new Output(sb.toString().stripTrailing() + "\n", ctx.images());
        }
    }

    private void appendParagraph(XWPFDocument doc, XWPFParagraph para,
                                  StringBuilder sb, ConvertContext ctx) {
        int heading = headingLevel(doc, para);
        String text = paragraphText(para, ctx);

        if (heading > 0) {
            if (!text.isBlank()) {
                sb.append("#".repeat(heading)).append(" ").append(text.trim()).append("\n\n");
            }
            return;
        }

        if (para.getNumID() != null) {
            int ilvl = para.getNumIlvl() != null ? para.getNumIlvl().intValue() : 0;
            String indent = "  ".repeat(ilvl);
            String marker = isOrderedList(doc, para) ? "1." : "-";
            sb.append(indent).append(marker).append(" ").append(text).append("\n");
            return;
        }

        if (!text.isBlank()) {
            sb.append(text).append("\n\n");
        } else {
            sb.append("\n");
        }
    }

    private String paragraphText(XWPFParagraph para, ConvertContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : para.getRuns()) {
            List<XWPFPicture> pics = run.getEmbeddedPictures();
            if (!pics.isEmpty()) {
                for (XWPFPicture pic : pics) {
                    String filename = resolveImageFilename(pic.getPictureData(), ctx);
                    sb.append("![이미지](").append(filename).append(")");
                }
                continue;
            }

            String text = run.getText(0);
            if (text == null || text.isEmpty()) continue;

            if (run instanceof XWPFHyperlinkRun h) {
                String url = resolveHyperlinkUrl(para.getDocument(), h);
                if (url != null) {
                    sb.append("[").append(text).append("](").append(url).append(")");
                } else {
                    sb.append(text);
                }
                continue;
            }

            boolean bold = run.isBold();
            boolean italic = run.isItalic();
            if (bold && italic) sb.append("***").append(text).append("***");
            else if (bold)      sb.append("**").append(text).append("**");
            else if (italic)    sb.append("*").append(text).append("*");
            else                sb.append(text);
        }
        return sb.toString();
    }

    private String resolveImageFilename(XWPFPictureData picData, ConvertContext ctx) {
        return ctx.picNames().computeIfAbsent(picData, pd -> {
            String ext = pd.suggestFileExtension();
            String name = "image_" + (ctx.images().size() + 1)
                        + (ext.isEmpty() ? "" : "." + ext);
            ctx.images().put(name, pd.getData());
            return name;
        });
    }

    private String resolveHyperlinkUrl(XWPFDocument doc, XWPFHyperlinkRun run) {
        try {
            return doc.getPackagePart()
                .getRelationship(run.getHyperlinkId())
                .getTargetURI().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private int headingLevel(XWPFDocument doc, XWPFParagraph para) {
        String styleId = para.getStyleID();
        if (styleId == null) return 0;
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

    private boolean isOrderedList(XWPFDocument doc, XWPFParagraph para) {
        try {
            BigInteger numId = para.getNumID();
            if (numId == null) return false;
            XWPFNumbering numbering = doc.getNumbering();
            if (numbering == null) return false;
            XWPFNum num = numbering.getNum(numId);
            if (num == null) return false;
            BigInteger abstractNumId = num.getCTNum().getAbstractNumId().getVal();
            XWPFAbstractNum abstractNum = numbering.getAbstractNum(abstractNumId);
            if (abstractNum == null) return false;
            int ilvl = para.getNumIlvl() != null ? para.getNumIlvl().intValue() : 0;
            CTLvl lvl = abstractNum.getCTAbstractNum().getLvlArray(ilvl);
            if (lvl == null || lvl.getNumFmt() == null) return false;
            return !"bullet".equals(lvl.getNumFmt().getVal().toString());
        } catch (Exception e) {
            return false;
        }
    }

    private void appendTable(XWPFTable table, StringBuilder sb, ConvertContext ctx) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return;

        sb.append("\n");
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            sb.append("|");
            for (XWPFTableCell cell : row.getTableCells()) {
                String text = cell.getParagraphs().stream()
                    .map(p -> paragraphText(p, ctx).trim())
                    .filter(t -> !t.isEmpty())
                    .collect(Collectors.joining(" "));
                sb.append(" ").append(text).append(" |");
            }
            sb.append("\n");
            if (i == 0) {
                sb.append("|");
                for (int j = 0; j < row.getTableCells().size(); j++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
    }
}
