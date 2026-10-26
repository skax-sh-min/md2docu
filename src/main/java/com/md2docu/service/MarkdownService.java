package com.md2docu.service;

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.AttributeProvider;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html.IndependentAttributeProviderFactory;
import com.vladsch.flexmark.html.renderer.AttributablePart;
import com.vladsch.flexmark.html.renderer.LinkResolverContext;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        this.renderer = HtmlRenderer.builder(options)
            .attributeProviderFactory(new IndependentAttributeProviderFactory() {
                @Override
                public AttributeProvider apply(LinkResolverContext context) {
                    return (node, part, attributes) -> {
                        if (part == AttributablePart.NODE && node instanceof Heading heading
                                && heading.isExplicitAnchorRefId()) {
                            attributes.replaceValue("id", heading.getAnchorRefId());
                        }
                    };
                }
            }).build();
    }

    public String toHtml(String markdown, boolean generateToc, boolean numberHeadings) {
        Node document = parser.parse(markdown);
        if (!generateToc && !numberHeadings) return renderer.render(document);

        List<Heading> headings = new ArrayList<>();
        Heading firstTitle = null;
        for (Node node : document.getChildren()) {
            if (node instanceof Heading heading) {
                if (heading.getLevel() == 1) {
                    if (firstTitle == null) firstTitle = heading;
                } else {
                    headings.add(heading);
                }
            }
        }
        if (headings.isEmpty()) return renderer.render(document);

        Set<String> usedIds = generateToc
            ? new HashSet<>(Jsoup.parseBodyFragment(renderer.render(document)).select("[id]").eachAttr("id"))
            : new HashSet<>();
        Element toc = new Element("div").addClass("toc");
        int[] counters = new int[7];
        int nextId = 1;
        for (Heading heading : headings) {
            String text = generateToc ? Jsoup.parseBodyFragment(renderer.render(heading)).text() : "";
            String prefix = numberHeadings ? nextNumber(counters, heading.getLevel()) + " " : "";
            if (numberHeadings) heading.prependChild(new Text(prefix));
            if (generateToc) {
                String id;
                do {
                    id = "toc-" + nextId++;
                } while (!usedIds.add(id));
                heading.setAnchorRefId(id);
                heading.setExplicitAnchorRefId(true);
                Element item = toc.appendElement("p").addClass("toc-item")
                    .addClass("toc-l" + heading.getLevel());
                item.append("&nbsp;&nbsp;".repeat((heading.getLevel() - 2) * 2));
                item.appendElement("a").attr("href", "#" + id).text(prefix + text);
            }
        }
        if (!generateToc) return renderer.render(document);

        BasedSequence tocHtml = BasedSequence.of(toc.outerHtml() + "\n");
        HtmlBlock tocBlock = new HtmlBlock(tocHtml, List.of(tocHtml));
        if (firstTitle == null) {
            document.prependChild(tocBlock);
        } else {
            firstTitle.insertAfter(tocBlock);
        }
        return renderer.render(document);
    }

    private String nextNumber(int[] counters, int level) {
        for (int parent = 2; parent < level; parent++) {
            if (counters[parent] == 0) counters[parent] = 1;
        }
        counters[level]++;
        Arrays.fill(counters, level + 1, counters.length, 0);
        StringBuilder number = new StringBuilder();
        for (int current = 2; current <= level; current++) {
            number.append(counters[current]).append('.');
        }
        return number.toString();
    }
}
