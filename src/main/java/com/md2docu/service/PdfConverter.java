package com.md2docu.service;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertWarning;
import com.md2docu.model.UserSettings;
import com.md2docu.util.ImageResolver;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PdfConverter {

    private static final Pattern IMG_PATTERN =
        Pattern.compile("<img([^>]*?)\\ssrc=\"([^\"]+)\"([^>]*?)/?>");

    private static final Pattern IMG_REMOVE_PATTERN =
        Pattern.compile("<img[^>]*?>");

    private static final Pattern LOCAL_LINK_PATTERN =
        Pattern.compile("<a\\s[^>]*?href=\"(?!https?://)(?!#)(.*?)\"[^>]*?>(.*?)</a>");

    private final ImageResolver imageResolver;
    private final UserSettingsService settingsService;

    public PdfConverter(ImageResolver imageResolver, UserSettingsService settingsService) {
        this.imageResolver = imageResolver;
        this.settingsService = settingsService;
    }

    public byte[] convert(String html, ConvertOptions options, Path basePath, List<ConvertWarning> warnings) throws IOException {
        String sanitized = sanitizeForPdf(html);
        String processed = processImages(sanitized, options, basePath, warnings);
        String processedLinks = processLinks(processed, options, warnings);
        String fullHtml = wrapHtml(processedLinks, options);

        Document jsoupDoc = Jsoup.parse(fullHtml);
        jsoupDoc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();

        // Windows: Malgun Gothic
        tryAddFont(builder, "C:/Windows/Fonts/malgun.ttf", "Malgun Gothic");
        tryAddFont(builder, "C:/Windows/Fonts/malgunbd.ttf", "Malgun Gothic");
        // Linux: Noto Sans CJK
        tryAddFont(builder, "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc", "Noto Sans CJK");

        builder.useUriResolver((baseUri, uri) -> {
            if (uri == null) return null;
            String lower = uri.toLowerCase();
            if (lower.startsWith("file:") || lower.startsWith("jar:")) return null;
            return uri;
        });

        builder.withW3cDocument(new W3CDom().fromJsoup(jsoupDoc), "/");
        builder.toStream(out);
        builder.run();

        return out.toByteArray();
    }

    private String sanitizeForPdf(String html) {
        return Jsoup.clean(html, Safelist.relaxed()
            .addTags("div", "span", "hr", "del", "table", "thead", "tbody", "tr", "th", "td")
            .addAttributes(":all", "class", "id"));
    }

    private void tryAddFont(PdfRendererBuilder builder, String path, String family) {
        File f = new File(path);
        if (f.exists()) {
            builder.useFont(f, family);
        }
    }

    private String processImages(String html, ConvertOptions options, Path basePath, List<ConvertWarning> warnings) {
        if (!options.isIncludeImages()) {
            return IMG_REMOVE_PATTERN.matcher(html).replaceAll("<span class=\"img-removed\">[이미지 제외됨]</span>");
        }

        Matcher m = IMG_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String before = m.group(1);
            String src = m.group(2);
            String after = m.group(3);

            String dataUri = imageResolver.resolveToBase64DataUri(src, basePath, warnings, options.getRemoteImageTimeout());
            String replacement;
            if (dataUri != null) {
                replacement = "<img" + before + " src=\"" + dataUri + "\"" + after + "/>";
            } else {
                replacement = "<span class=\"img-error\">[이미지 로드 실패: " + escapeHtml(src) + "]</span>";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String processLinks(String html, ConvertOptions options, List<ConvertWarning> warnings) {
        if ("ignore".equals(options.getLinkStrategy())) {
            return html.replaceAll("<a\\s[^>]*?>", "").replaceAll("</a>", "");
        }
        if ("warn".equals(options.getLinkStrategy())) {
            Matcher m = LOCAL_LINK_PATTERN.matcher(html);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String href = m.group(1);
                String text = m.group(2);
                warnings.add(ConvertWarning.attachmentNotFound(href));
                m.appendReplacement(sb, Matcher.quoteReplacement(
                    text + " <span class=\"attach-warn\">[⚠ 첨부파일 미포함: " + escapeHtml(href) + "]</span>"
                ));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        return html;
    }

    private String wrapHtml(String body, ConvertOptions options) {
        UserSettings s = settingsService.get();
        String css = buildCss(options, s);
        return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\"/>\n"
            + "<style>\n" + css + "</style>\n"
            + "</head>\n<body>\n" + body + "\n</body>\n</html>";
    }

    private String buildCss(ConvertOptions options, UserSettings s) {
        String margin = s.getMarginTopMm() + "mm " + s.getMarginRightMm() + "mm "
                      + s.getMarginBottomMm() + "mm " + s.getMarginLeftMm() + "mm";
        return "@page { size: " + options.getPageSize().toUpperCase() + "; margin: " + margin + "; }\n"
            + "body { font-family: " + s.getBodyFontFamily() + "; font-size: " + s.getBodyFontSizePt() + "pt; line-height: " + s.getLineHeight() + "; color: #333; }\n"
            + "p { margin: 0 0 8pt 0; }\n"
            + "h1 { font-size: " + s.getH1FontSizePt() + "pt; border-bottom: 2px solid #333; padding-bottom: 4px; margin: 28pt 0 20pt 0; }\n"
            + "h2 { font-size: " + s.getH2FontSizePt() + "pt; border-bottom: 1px solid #ccc; padding-bottom: 2px; margin: 24pt 0 12pt 0; }\n"
            + "h3 { font-size: " + s.getH3FontSizePt() + "pt; margin: 20pt 0 10pt 0; }\n"
            + "h4, h5, h6 { font-size: " + s.getH4FontSizePt() + "pt; margin: 16pt 0 8pt 0; }\n"
            + ".toc { margin: 20pt 0 28pt 0; padding: 12pt 16pt; border: 1px solid #ddd; background: #f9f9f9; }\n"
            + ".toc-item { margin: 3pt 0; line-height: 1.6; }\n"
            + "pre { background: #f5f5f5; border: 1px solid #ddd; border-radius: 4px; padding: 10px 14px; font-family: 'Courier New', monospace; font-size: " + s.getCodeFontSizePt() + "pt; white-space: pre-wrap; word-break: break-all; }\n"
            + "code { font-family: 'Courier New', monospace; font-size: " + s.getCodeFontSizePt() + "pt; background: #f0f0f0; padding: 1px 4px; border-radius: 2px; }\n"
            + "pre code { background: none; padding: 0; }\n"
            + "table { border-collapse: collapse; width: 100%; margin: 12px 0; }\n"
            + "th, td { border: 1px solid #ccc; padding: 6px 10px; }\n"
            + "th { background: #f0f0f0; font-weight: bold; }\n"
            + "blockquote { border-left: 4px solid #ccc; margin: 8px 0; padding: 4px 14px; color: #666; background: #fafafa; }\n"
            + "img { max-width: 100%; height: auto; }\n"
            + "a { color: #0066cc; }\n"
            + "hr { border: none; border-top: 1px solid #ccc; margin: 16px 0; }\n"
            + ".img-error { color: #c00; font-style: italic; font-size: 9pt; }\n"
            + ".img-removed { color: #999; font-style: italic; font-size: 9pt; }\n"
            + ".attach-warn { color: #c60; font-size: 9pt; }\n";
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
