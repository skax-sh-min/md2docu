package com.md2docu.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConversionRequest(
    JsonNode markdown, JsonNode url, JsonNode pageSize, JsonNode includeImages,
    JsonNode linkStrategy, JsonNode remoteImageTimeout, JsonNode generateToc, JsonNode numberHeadings
) {
    public String markdownText() {
        String text = string(markdown, "markdown");
        return text == null ? "" : text;
    }

    public String sourceUrl() {
        String text = string(url, "url");
        return text == null ? "" : text.trim();
    }

    public boolean previewNumbering() {
        return Boolean.TRUE.equals(bool(numberHeadings, "numberHeadings"));
    }

    public ConvertOptions options() {
        return options(string(pageSize, "pageSize"), bool(includeImages, "includeImages"),
            string(linkStrategy, "linkStrategy"), integer(remoteImageTimeout, "remoteImageTimeout"),
            bool(generateToc, "generateToc"), bool(numberHeadings, "numberHeadings"));
    }

    public static ConvertOptions formOptions(String pageSize, String includeImages, String linkStrategy,
                                            String timeout, String toc, String numbering) {
        return options(pageSize, formBoolean(includeImages, "includeImages"), linkStrategy,
            formInteger(timeout), formBoolean(toc, "generateToc"), formBoolean(numbering, "numberHeadings"));
    }

    public static String format(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("pdf", "docx").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "지원하지 않는 변환 형식입니다: " + value + " (지원: pdf, docx)");
        }
        return normalized;
    }

    public static Boolean formBoolean(String value, String field) {
        if (value == null) return null;
        if ("true".equalsIgnoreCase(value.trim())) return true;
        if ("false".equalsIgnoreCase(value.trim())) return false;
        throw invalid(field, "true 또는 false");
    }

    private static ConvertOptions options(String page, Boolean images, String links, Integer timeout,
                                          Boolean toc, Boolean numbering) {
        ConvertOptions options = new ConvertOptions();
        if (page != null) {
            String normalized = page.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("A4", "LETTER").contains(normalized)) throw invalid("pageSize", "A4 또는 LETTER");
            options.setPageSize(normalized);
        }
        if (links != null) {
            String normalized = links.trim().toLowerCase(Locale.ROOT);
            if (!Set.of("keep", "warn", "ignore").contains(normalized)) throw invalid("linkStrategy", "keep, warn 또는 ignore");
            options.setLinkStrategy(normalized);
        }
        if (images != null) options.setIncludeImages(images);
        if (timeout != null) options.setRemoteImageTimeout(Math.max(1000, Math.min(30000, timeout)));
        if (toc != null) options.setGenerateToc(toc);
        if (numbering != null) options.setNumberHeadings(numbering);
        return options;
    }

    private static String string(JsonNode value, String field) {
        if (value == null) return null;
        if (!value.isTextual()) throw invalid(field, "문자열");
        return value.asText();
    }

    private static Boolean bool(JsonNode value, String field) {
        if (value == null) return null;
        if (!value.isBoolean()) throw invalid(field, "boolean");
        return value.booleanValue();
    }

    private static Integer integer(JsonNode value, String field) {
        if (value == null) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid(field, "32비트 정수");
        return value.intValue();
    }

    private static Integer formInteger(String value) {
        if (value == null) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw invalid("remoteImageTimeout", "32비트 정수");
        }
    }

    private static ResponseStatusException invalid(String field, String expected) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "'" + field + "' 필드는 " + expected + " 값이어야 합니다.");
    }
}
