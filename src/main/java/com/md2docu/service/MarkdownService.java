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

    private record TocData(
        int h1LineIdx,
        List<Integer> levels,
        List<String> texts,
        List<Integer> lineIndices,
        List<String> numbers) {}

    private String insertCustomToc(String markdown, boolean numberHeadings) {
        String[] lines = markdown.split("\n", -1);
        TocData toc = collectHeadings(lines, numberHeadings);
        if (toc.levels().isEmpty()) return markdown;
        String tocHtml = buildTocHtml(toc);
        return assembleTocMarkdown(lines, toc, tocHtml);
    }

    // 1st pass: 헤딩 수집 + 번호 계산 (펜스 코드 블록 내부 제외)
    private TocData collectHeadings(String[] lines, boolean numberHeadings) {
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

        int[] counters = new int[7];
        List<String> numbers = new ArrayList<>();
        for (int lv : levels) {
            if (numberHeadings) {
                counters[lv]++;
                for (int j = lv + 1; j <= 6; j++) counters[j] = 0;
                StringBuilder num = new StringBuilder();
                for (int j = 2; j <= lv; j++) num.append(counters[j]).append(".");
                numbers.add(num.toString());
            } else {
                numbers.add("");
            }
        }

        return new TocData(h1LineIdx, levels, texts, lineIndices, numbers);
    }

    // 목차 HTML 블록 생성
    private String buildTocHtml(TocData toc) {
        StringBuilder sb = new StringBuilder("<div class=\"toc\">\n");
        for (int i = 0; i < toc.levels().size(); i++) {
            int lv = toc.levels().get(i);
            String indent = "&nbsp;&nbsp;".repeat((lv - 2) * 2);
            String anchorId = "toc-" + (i + 1);
            String num = toc.numbers().get(i);
            String numPrefix = num.isEmpty() ? "" : num + " ";
            sb.append(String.format(
                "<p class=\"toc-item toc-l%d\">%s<a href=\"#%s\">%s%s</a></p>\n",
                lv, indent, anchorId, numPrefix, escapeHtml(toc.texts().get(i))));
        }
        sb.append("</div>");
        return sb.toString();
    }

    // 2nd pass: 마크다운 재조립 + 번호 붙인 헤딩 교체
    private String assembleTocMarkdown(String[] lines, TocData toc, String tocHtml) {
        Map<Integer, Integer> lineToIdx = new HashMap<>();
        for (int i = 0; i < toc.lineIndices().size(); i++) lineToIdx.put(toc.lineIndices().get(i), i);

        StringBuilder sb = new StringBuilder();
        boolean tocInserted = false;
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLen = 0;

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
                if (level >= 1 && sb.length() > 0) {
                    boolean endsWithDoubleNewline = sb.length() >= 2
                        && sb.charAt(sb.length() - 1) == '\n'
                        && sb.charAt(sb.length() - 2) == '\n';
                    if (!endsWithDoubleNewline) sb.append("\n");
                }

                if (level == 1 && i == toc.h1LineIdx()) {
                    sb.append("\n\n").append(line).append("\n\n");
                    sb.append(tocHtml).append("\n\n");
                    tocInserted = true;
                } else if (lineToIdx.containsKey(i)) {
                    int idx = lineToIdx.get(i);
                    int lv = toc.levels().get(idx);
                    String anchorId = "toc-" + (idx + 1);
                    String num = toc.numbers().get(idx);
                    String headingText = (num.isEmpty() ? "" : num + " ") + escapeHtml(toc.texts().get(idx));
                    sb.append(String.format("<h%d id=\"%s\">%s</h%d>\n", lv, anchorId, headingText, lv));
                } else {
                    sb.append(line).append("\n");
                }
            } else {
                sb.append(line).append("\n");
                if (isFenceClose(line, fenceChar, fenceLen)) inFence = false;
            }
        }

        if (!tocInserted) return tocHtml + "\n\n" + String.join("\n", lines);
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
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default  -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
