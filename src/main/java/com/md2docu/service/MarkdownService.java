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

    public String toHtml(String markdown, boolean generateToc) {
        if (generateToc) {
            markdown = insertCustomToc(markdown);
        }
        Node document = parser.parse(markdown);
        return renderer.render(document);
    }

    private String insertCustomToc(String markdown) {
        String[] lines = markdown.split("\n", -1);

        // H1 위치와 H2+ 헤딩 수집
        int h1LineIdx = -1;
        List<Integer> levels = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Integer> lineIndices = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            int level = atxLevel(lines[i]);
            if (level == 1 && h1LineIdx < 0) {
                h1LineIdx = i;
            } else if (level >= 2) {
                levels.add(level);
                texts.add(lines[i].substring(level).trim());
                lineIndices.add(i);
            }
        }

        if (levels.isEmpty()) return markdown;

        // 헤딩별 번호 계산: 1., 1.1., 1.1.1. ...
        int[] counters = new int[7]; // 인덱스 2~6 → H2~H6
        List<String> numbers = new ArrayList<>();
        for (int lv : levels) {
            counters[lv]++;
            for (int j = lv + 1; j <= 6; j++) counters[j] = 0;
            StringBuilder num = new StringBuilder();
            for (int j = 2; j <= lv; j++) num.append(counters[j]).append(".");
            numbers.add(num.toString());
        }

        // 목차 HTML 블록 생성 (링크 포함)
        StringBuilder tocHtml = new StringBuilder("<div class=\"toc\">\n");
        for (int i = 0; i < levels.size(); i++) {
            int lv = levels.get(i);
            String indent = "&nbsp;&nbsp;".repeat((lv - 2) * 2);
            String anchorId = "toc-" + (i + 1);
            tocHtml.append(String.format(
                "<p class=\"toc-item toc-l%d\">%s<a href=\"#%s\">%s %s</a></p>\n",
                lv, indent, anchorId, numbers.get(i), texts.get(i)));
        }
        tocHtml.append("</div>");

        // 줄 번호 → 헤딩 인덱스 맵
        Map<Integer, Integer> lineToIdx = new HashMap<>();
        for (int i = 0; i < lineIndices.size(); i++) {
            lineToIdx.put(lineIndices.get(i), i);
        }

        // 마크다운 재조립
        StringBuilder sb = new StringBuilder();
        boolean tocInserted = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int level = atxLevel(line);

            // 헤딩 앞 줄바꿈
            if (level >= 1 && sb.length() > 0 && !sb.toString().endsWith("\n\n")) {
                sb.append("\n");
            }

            if (level == 1 && i == h1LineIdx) {
                // 제목 앞뒤 2줄, 목차 삽입
                sb.append("\n\n").append(line).append("\n\n");
                sb.append(tocHtml).append("\n\n");
                tocInserted = true;
            } else if (lineToIdx.containsKey(i)) {
                // 본문 헤딩을 번호+앵커 포함 HTML로 교체
                int idx = lineToIdx.get(i);
                int lv = levels.get(idx);
                String anchorId = "toc-" + (idx + 1);
                String numberedText = numbers.get(idx) + " " + texts.get(idx);
                sb.append(String.format("<h%d id=\"%s\">%s</h%d>\n", lv, anchorId, numberedText, lv));
            } else {
                sb.append(line).append("\n");
            }
        }

        // H1이 없으면 맨 앞에 목차 삽입
        if (!tocInserted) {
            return tocHtml + "\n\n" + markdown;
        }

        return sb.toString();
    }

    private int atxLevel(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') n++;
        if (n == 0 || n > 6) return 0;
        return (n < line.length() && line.charAt(n) == ' ') ? n : 0;
    }
}
