package com.md2docu.service;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MarkdownService {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownService() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create()
        ));
        options.set(HtmlRenderer.SOFT_BREAK, "<br />\n");

        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
    }

    public String toHtml(String markdown, boolean generateToc, boolean numberHeadings) {
        if (generateToc) {
            markdown = insertCustomToc(markdown, numberHeadings);
        } else if (numberHeadings) {
            markdown = insertHeadingNumbers(markdown);
        }
        Node document = parser.parse(markdown);
        return renderer.render(document);
    }

    private String insertCustomToc(String markdown, boolean numberHeadings) {
        String[] lines = markdown.split("\n", -1);

        // H1 위치와 H2+ 헤딩 수집 (펜스 코드 블록 내부 제외)
        int h1LineIdx = -1;
        List<Integer> levels = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Integer> lineIndices = new ArrayList<>();

        boolean inFence = false;
        char fenceChar = 0;
        int fenceLen = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!inFence) {
                int[] fd = detectFenceOpen(line);
                if (fd != null) {
                    inFence = true; fenceChar = (char) fd[0]; fenceLen = fd[1];
                } else {
                    int level = atxLevel(line);
                    if (level == 1 && h1LineIdx < 0) h1LineIdx = i;
                    else if (level >= 2) { levels.add(level); texts.add(atxText(line, level)); lineIndices.add(i); }
                }
            } else if (isFenceClose(line, fenceChar, fenceLen)) {
                inFence = false;
            }
        }

        if (levels.isEmpty()) return markdown;

        boolean useNumbers = numberHeadings;

        // 헤딩별 번호 계산: 1., 1.1., 1.1.1. ...
        int[] counters = new int[7]; // 인덱스 2~6 → H2~H6
        List<String> numbers = new ArrayList<>();
        for (int lv : levels) {
            if (useNumbers) {
                counters[lv]++;
                for (int j = lv + 1; j <= 6; j++) counters[j] = 0;
                StringBuilder num = new StringBuilder();
                for (int j = 2; j <= lv; j++) num.append(counters[j]).append(".");
                numbers.add(num.toString());
            } else {
                numbers.add("");
            }
        }

        // 목차 HTML 블록 생성 (링크 포함)
        StringBuilder tocHtml = new StringBuilder("<div class=\"toc\">\n");
        for (int i = 0; i < levels.size(); i++) {
            int lv = levels.get(i);
            String indent = "&nbsp;&nbsp;".repeat((lv - 2) * 2);
            String anchorId = "toc-" + (i + 1);
            String numPrefix = useNumbers ? numbers.get(i) + " " : "";
            tocHtml.append(String.format(
                "<p class=\"toc-item toc-l%d\">%s<a href=\"#%s\">%s%s</a></p>\n",
                lv, indent, anchorId, numPrefix, escapeHtml(texts.get(i))));
        }
        tocHtml.append("</div>");

        // 줄 번호 → 헤딩 인덱스 맵
        Map<Integer, Integer> lineToIdx = new HashMap<>();
        for (int i = 0; i < lineIndices.size(); i++) lineToIdx.put(lineIndices.get(i), i);

        // 마크다운 재조립 (펜스 코드 블록 내부는 그대로 유지)
        StringBuilder sb = new StringBuilder();
        boolean tocInserted = false;
        inFence = false; fenceChar = 0; fenceLen = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (!inFence) {
                int[] fd = detectFenceOpen(line);
                if (fd != null) {
                    inFence = true; fenceChar = (char) fd[0]; fenceLen = fd[1];
                    sb.append(line).append("\n");
                    continue;
                }

                int level = atxLevel(line);
                if (level >= 1 && sb.length() > 0 && !sb.toString().endsWith("\n\n")) sb.append("\n");

                if (level == 1 && i == h1LineIdx) {
                    sb.append("\n\n").append(line).append("\n\n");
                    sb.append(tocHtml).append("\n\n");
                    tocInserted = true;
                } else if (lineToIdx.containsKey(i)) {
                    int idx = lineToIdx.get(i);
                    int lv = levels.get(idx);
                    String anchorId = "toc-" + (idx + 1);
                    String numPrefix = useNumbers ? numbers.get(idx) + " " : "";
                    String headingText = numPrefix + escapeHtml(texts.get(idx));
                    sb.append(String.format("<h%d id=\"%s\">%s</h%d>\n", lv, anchorId, headingText, lv));
                } else {
                    sb.append(line).append("\n");
                }
            } else {
                sb.append(line).append("\n");
                if (isFenceClose(line, fenceChar, fenceLen)) inFence = false;
            }
        }

        if (!tocInserted) return tocHtml + "\n\n" + markdown;
        return sb.toString();
    }

    // 번호만 붙이고 목차 블록은 생성하지 않음
    private String insertHeadingNumbers(String markdown) {
        String[] lines = markdown.split("\n", -1);

        List<Integer> levels = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Integer> lineIndices = new ArrayList<>();

        boolean inFence = false;
        char fenceChar = 0;
        int fenceLen = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!inFence) {
                int[] fd = detectFenceOpen(line);
                if (fd != null) {
                    inFence = true; fenceChar = (char) fd[0]; fenceLen = fd[1];
                } else {
                    int level = atxLevel(line);
                    if (level >= 2) { levels.add(level); texts.add(atxText(line, level)); lineIndices.add(i); }
                }
            } else if (isFenceClose(line, fenceChar, fenceLen)) {
                inFence = false;
            }
        }

        if (levels.isEmpty()) return markdown;

        // 번호 계산
        int[] counters = new int[7];
        List<String> numbers = new ArrayList<>();
        for (int lv : levels) {
            counters[lv]++;
            for (int j = lv + 1; j <= 6; j++) counters[j] = 0;
            StringBuilder num = new StringBuilder();
            for (int j = 2; j <= lv; j++) num.append(counters[j]).append(".");
            numbers.add(num.toString());
        }

        Map<Integer, Integer> lineToIdx = new HashMap<>();
        for (int i = 0; i < lineIndices.size(); i++) lineToIdx.put(lineIndices.get(i), i);

        // 마크다운 재조립 (헤딩에만 번호 삽입, HTML 변환 없음)
        StringBuilder sb = new StringBuilder();
        inFence = false; fenceChar = 0; fenceLen = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!inFence) {
                int[] fd = detectFenceOpen(line);
                if (fd != null) {
                    inFence = true; fenceChar = (char) fd[0]; fenceLen = fd[1];
                    sb.append(line).append("\n");
                    continue;
                }
                Integer idx = lineToIdx.get(i);
                if (idx != null) {
                    int lv = levels.get(idx);
                    sb.append("#".repeat(lv)).append(" ")
                      .append(numbers.get(idx)).append(" ")
                      .append(texts.get(idx)).append("\n");
                } else {
                    sb.append(line).append("\n");
                }
            } else {
                sb.append(line).append("\n");
                if (isFenceClose(line, fenceChar, fenceLen)) inFence = false;
            }
        }
        return sb.toString();
    }

    // 펜스 코드 블록 시작 감지: [펜스문자(int), 펜스길이] 반환, 아니면 null
    private int[] detectFenceOpen(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ' && spaces < 3) spaces++;
        String s = line.substring(spaces);
        if (s.isEmpty()) return null;
        char c = s.charAt(0);
        if (c != '`' && c != '~') return null;
        int len = 0;
        while (len < s.length() && s.charAt(len) == c) len++;
        return len >= 3 ? new int[]{c, len} : null;
    }

    // 펜스 코드 블록 닫힘 감지
    private boolean isFenceClose(String line, char fenceChar, int fenceLen) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ' && spaces < 3) spaces++;
        String s = line.substring(spaces);
        int len = 0;
        while (len < s.length() && s.charAt(len) == fenceChar) len++;
        return len >= fenceLen && s.substring(len).trim().isEmpty();
    }

    private int atxLevel(String line) {
        int start = 0;
        while (start < 3 && start < line.length() && line.charAt(start) == ' ') start++;
        int i = start, n = 0;
        while (i < line.length() && line.charAt(i) == '#') { i++; n++; }
        if (n == 0 || n > 6) return 0;
        return (i < line.length() && line.charAt(i) == ' ') ? n : 0;
    }

    private String atxText(String line, int level) {
        int start = 0;
        while (start < 3 && start < line.length() && line.charAt(start) == ' ') start++;
        return line.substring(start + level).trim();
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
