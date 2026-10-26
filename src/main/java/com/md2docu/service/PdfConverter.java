package com.md2docu.service;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertWarning;
import com.md2docu.model.UserSettings;
import com.md2docu.util.ImageResolver;
import com.md2docu.util.ImageResolver.LocalImagePaths;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

@Service
public class PdfConverter {

    private static final Logger log = Logger.getLogger(PdfConverter.class.getName());
    private static final String PDF_STATIC_CSS = loadCss();

    private static String loadCss() {
        try (InputStream is = PdfConverter.class.getResourceAsStream("/pdf-style.css")) {
            if (is == null) {
                log.warning("pdf-style.css 를 클래스패스에서 찾을 수 없음 — 정적 스타일 없이 PDF 렌더링됨");
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("pdf-style.css 읽기 실패 — " + e.getMessage());
            return "";
        }
    }

    private final ImageResolver imageResolver;
    private final UserSettingsService settingsService;
    private final FontService fontService;

    public PdfConverter(ImageResolver imageResolver, UserSettingsService settingsService,
                        FontService fontService) {
        this.imageResolver = imageResolver;
        this.settingsService = settingsService;
        this.fontService = fontService;
    }

    public byte[] convert(String html, ConvertOptions options, LocalImagePaths imagePaths, List<ConvertWarning> warnings) throws IOException {
        Document sanitized = sanitizeForPdf(html);
        processImages(sanitized, options, imagePaths, warnings);
        processLinks(sanitized, options, warnings);
        String fullHtml = wrapHtml(sanitized.body().html(), options);

        Document jsoupDoc = Jsoup.parse(fullHtml);
        jsoupDoc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();

        for (FontService.FontEntry entry : fontService.getAvailableFonts()) {
            tryAddFont(builder, entry.path(), entry.family());
        }

        builder.useUriResolver((baseUri, uri) -> {
            if (uri == null) return null;
            String lower = uri.toLowerCase(Locale.ROOT);
            if (lower.startsWith("file:") || lower.startsWith("jar:")) return null;
            return uri;
        });

        builder.withW3cDocument(new W3CDom().fromJsoup(jsoupDoc), "/");
        builder.toStream(out);
        builder.run();

        return out.toByteArray();
    }

    private Document sanitizeForPdf(String html) {
        // Resource processors validate URIs after structural cleaning.
        Safelist safelist = Safelist.relaxed()
            .addTags("div", "span", "hr", "del", "table", "thead", "tbody", "tr", "th", "td")
            .addAttributes(":all", "class", "id")
            .removeProtocols("img", "src", "http", "https")
            .removeProtocols("a", "href", "ftp", "http", "https", "mailto");
        return new Cleaner(safelist).clean(Jsoup.parseBodyFragment(html));
    }

    private void tryAddFont(PdfRendererBuilder builder, String path, String family) {
        File f = new File(path);
        if (f.exists()) {
            builder.useFont(f, family);
        }
    }

    private void processImages(Document document, ConvertOptions options, LocalImagePaths imagePaths,
                               List<ConvertWarning> warnings) {
        for (Element image : document.select("img")) {
            if (!options.isIncludeImages()) {
                image.replaceWith(new Element("span").addClass("img-removed").text("[이미지 제외됨]"));
                continue;
            }

            String src = image.attr("src").strip();
            URI uri = parseUri(src);
            String dataUri = null;
            int warningCount = warnings.size();
            if (src.isEmpty()) {
                warnings.add(ConvertWarning.imageNotFound(src));
            } else if (!isAllowedImageUri(uri, src)) {
                warnings.add(ConvertWarning.unsupportedResource(src));
            } else {
                String resolvedSrc = uri.getScheme() == null ? src
                    : uri.getScheme().toLowerCase(Locale.ROOT) + src.substring(src.indexOf(':'));
                dataUri = imageResolver.resolveToBase64DataUri(resolvedSrc, imagePaths, warnings,
                    options.getRemoteImageTimeout());
            }

            if (dataUri != null) {
                image.attr("src", dataUri);
            } else {
                if (warnings.size() == warningCount) {
                    warnings.add(ConvertWarning.imageNotFound(src));
                }
                image.replaceWith(new Element("span").addClass("img-error")
                    .text("[이미지 로드 실패: " + src + "]"));
            }
        }
    }

    private void processLinks(Document document, ConvertOptions options, List<ConvertWarning> warnings) {
        for (Element link : document.select("a")) {
            if ("ignore".equals(options.getLinkStrategy())) {
                link.tagName("span").removeAttr("href");
                continue;
            }
            if (!link.hasAttr("href")) continue;

            String href = link.attr("href").strip();
            URI uri = parseUri(href);
            if (!isAllowedLinkUri(uri)) {
                warnings.add(ConvertWarning.unsupportedResource(href));
                link.tagName("span").removeAttr("href");
            } else if (href.startsWith("#")) {
                link.attr("href", "#" + uri.getFragment());
            } else if ("warn".equals(options.getLinkStrategy()) && uri.getScheme() == null) {
                warnings.add(ConvertWarning.attachmentNotFound(href));
                link.appendText(" ").appendElement("span").addClass("attach-warn")
                    .text("[⚠ 첨부파일 미포함: " + href + "]");
                link.tagName("span").removeAttr("href");
            } else {
                link.attr("href", href);
            }
        }
    }

    private URI parseUri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private boolean isAllowedImageUri(URI uri, String src) {
        if (uri == null) return false;
        if (uri.getScheme() == null) {
            return uri.getRawAuthority() == null && uri.getPath() != null && !uri.getPath().isEmpty();
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return hasAllowedHost(uri);
        }
        if ("data".equals(scheme)) {
            int comma = src.indexOf(',');
            if (comma < 0) return false;
            String header = src.substring(0, comma).toLowerCase(Locale.ROOT);
            return header.startsWith("data:image/") && header.endsWith(";base64");
        }
        return false;
    }

    private boolean isAllowedLinkUri(URI uri) {
        if (uri == null) return false;
        if (uri.getScheme() == null) {
            return uri.getRawAuthority() == null
                && ((uri.getPath() != null && !uri.getPath().isEmpty()) || uri.getFragment() != null);
        }
        return switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
            case "http", "https", "ftp" -> hasAllowedHost(uri);
            case "mailto" -> !uri.getRawSchemeSpecificPart().isBlank();
            default -> false;
        };
    }

    private boolean hasAllowedHost(URI uri) {
        return uri.getHost() != null && uri.getUserInfo() == null && uri.getPort() <= 65535;
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
            + "h1 { font-size: " + s.getH1FontSizePt() + "pt; }\n"
            + "h2 { font-size: " + s.getH2FontSizePt() + "pt; }\n"
            + "h3 { font-size: " + s.getH3FontSizePt() + "pt; }\n"
            + "h4, h5, h6 { font-size: " + s.getH4FontSizePt() + "pt; }\n"
            + "pre, code { font-size: " + s.getCodeFontSizePt() + "pt; }\n"
            + PDF_STATIC_CSS;
    }

}
