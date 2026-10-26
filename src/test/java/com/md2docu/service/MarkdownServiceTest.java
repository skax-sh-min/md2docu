package com.md2docu.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownServiceTest {

    private final MarkdownService service = new MarkdownService();

    @ParameterizedTest
    @MethodSource("optionCombinations")
    void keepsTocAndNumberingIndependent(boolean hasH1, boolean toc, boolean numbers) {
        String markdown = (hasH1 ? "# Title\n\n" : "Introduction.\n\n")
            + "## First\n\nBody.\n\n### Child\n\n## Second";
        Document html = render(markdown, toc, numbers);
        assertThat(html.select("h2, h3").eachText()).containsExactly(
            (numbers ? "1. " : "") + "First",
            (numbers ? "1.1. " : "") + "Child",
            (numbers ? "2. " : "") + "Second"
        );
        if (toc) {
            assertTocTargets(html, 3);
            assertThat(html.select(".toc a").eachText())
                .containsExactlyElementsOf(html.select("h2, h3").eachText());
            if (hasH1) {
                assertThat(html.selectFirst("h1").nextElementSibling().hasClass("toc")).isTrue();
            } else {
                assertThat(html.body().child(0).hasClass("toc")).isTrue();
            }
        } else {
            assertThat(html.select(".toc")).isEmpty();
        }
        if (hasH1) assertThat(html.selectFirst("h1").text()).isEqualTo("Title");
    }

    private static Stream<Arguments> optionCombinations() {
        return Stream.of(false, true).flatMap(h1 -> Stream.of(false, true).flatMap(toc ->
            Stream.of(false, true).map(numbers -> Arguments.of(h1, toc, numbers))));
    }

    @ParameterizedTest
    @MethodSource("formatOptions")
    void preservesHeadingInlineFormattingAndUsesPlainTocLabels(boolean toc, boolean numbers) {
        Document html = render("# Title\n\n## **Bold** and `code` [Link][ref] ~~old~~ &amp; *italic*\n\n"
            + "[ref]: https://example.com", toc, numbers);
        Element heading = html.selectFirst("h2");
        assertThat(heading.selectFirst("strong").text()).isEqualTo("Bold");
        assertThat(heading.selectFirst("code").text()).isEqualTo("code");
        assertThat(heading.selectFirst("a").attr("href")).isEqualTo("https://example.com");
        assertThat(heading.selectFirst("del").text()).isEqualTo("old");
        assertThat(heading.selectFirst("em").text()).isEqualTo("italic");
        assertThat(heading.text()).isEqualTo((numbers ? "1. " : "") + "Bold and code Link old & italic");
        if (toc) {
            assertThat(html.selectFirst(".toc a").text()).isEqualTo(heading.text());
            assertThat(html.select(".toc strong, .toc code, .toc a a, .toc em, .toc del")).isEmpty();
        }
    }

    private static Stream<Arguments> formatOptions() {
        return Stream.of(false, true).flatMap(toc ->
            Stream.of(false, true).map(numbers -> Arguments.of(toc, numbers)));
    }

    @Test
    void assignsUniqueIdsWithoutCollidingWithExistingHtmlIds() {
        Document html = render("<p id=\"toc-1\">Existing</p>\n\n# toc-2\n\n"
            + "## Repeated <span id=\"toc-2\">Inline</span>\n\n## Repeated\n\n### Repeated",
            true, false);
        assertTocTargets(html, 3);
        List<String> ids = html.select("[id]").eachAttr("id");
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(html.getElementById("toc-1").text()).isEqualTo("Existing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"```", "~~~", "````", "~~~~"})
    void ignoresCodeFencesAndDoesNotRewriteTheirContents(String fence) {
        String code = "## Fake\n# Not a title\n```";
        if ("```".equals(fence)) code = "## Fake\n# Not a title\n~~~";
        Document html = render(fence + "\n" + code + "\n" + fence + "\n\n## Real", true, true);
        assertThat(html.select("h2").eachText()).containsExactly("1. Real");
        assertThat(html.selectFirst("pre code").wholeText()).isEqualTo(code + "\n");
        assertTocTargets(html, 1);
        assertThat(html.selectFirst(".toc a").text()).isEqualTo("1. Real");
    }

    @Test
    void leavesUnclosedFencesAndIndentedCodeUnchanged() {
        Document indented = render("    ## Code heading\n\n## Real", true, true);
        assertThat(indented.select("h2").eachText()).containsExactly("1. Real");
        assertThat(indented.selectFirst("pre code").text()).contains("## Code heading");
        Document unclosed = render("```text\n## Only code", true, true);
        assertThat(unclosed.select("h1, h2, .toc")).isEmpty();
        assertThat(unclosed.selectFirst("pre code").text()).contains("## Only code");
    }

    @ParameterizedTest
    @MethodSource("formatOptions")
    void treatsSetextHeadingsLikeAtxHeadings(boolean toc, boolean numbers) {
        Document html = render("Title\n=====\n\n**Chapter** `code`\n------------------\n\n### Child",
            toc, numbers);
        assertThat(html.selectFirst("h1").text()).isEqualTo("Title");
        assertThat(html.selectFirst("h2").text()).isEqualTo((numbers ? "1. " : "") + "Chapter code");
        assertThat(html.selectFirst("h2 strong").text()).isEqualTo("Chapter");
        assertThat(html.selectFirst("h2 code").text()).isEqualTo("code");
        assertThat(html.selectFirst("h3").text()).isEqualTo((numbers ? "1.1. " : "") + "Child");
        if (toc) assertTocTargets(html, 2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fillsSkippedParentsWithOneWithoutChangingHeadingLevels(boolean toc) {
        Document html = render("### Start\n\n##### Deep\n\n## Next\n\n#### Jump\n\n### Sibling\n\n"
            + "###### Last", toc, true);
        assertThat(html.select("h2, h3, h4, h5, h6").eachText()).containsExactly(
            "1.1. Start", "1.1.1.1. Deep", "2. Next",
            "2.1.1. Jump", "2.2. Sibling", "2.2.1.1.1. Last"
        );
        assertThat(html.select("h2, h3, h4, h5, h6").stream().map(Element::tagName).toList())
            .containsExactly("h3", "h5", "h2", "h4", "h3", "h6");
        if (toc) assertTocTargets(html, 6);
    }

    @Test
    void followsParsedHeadingSyntaxInsteadOfCountingHashCharacters() {
        Document html = render("# Title\n\n## Closed ##\n\n###\tTab\n\n"
            + "Escaped \\## not a heading\n\n###NoSpace", true, true);
        assertThat(html.select("h2, h3").eachText()).containsExactly("1. Closed", "1.1. Tab");
        assertThat(html.select("p").eachText()).contains("Escaped ## not a heading", "###NoSpace");
        assertTocTargets(html, 2);
    }

    @Test
    void onlyNumbersDocumentLevelMarkdownHeadings() {
        Document html = render("# Title\n\n> ## Quoted\n\n- Item\n\n  ### Nested\n\n"
            + "<h2 id=\"manual\">HTML heading</h2>\n\n## Real", true, true);
        assertThat(html.selectFirst("blockquote h2").text()).isEqualTo("Quoted");
        assertThat(html.selectFirst("li h3").text()).isEqualTo("Nested");
        assertThat(html.getElementById("manual").text()).isEqualTo("HTML heading");
        assertThat(html.select(".toc a").eachText()).containsExactly("1. Real");
        assertTocTargets(html, 1);
    }

    @Test
    void insertsOneTocAndKeepsNumberingAcrossAdditionalH1Headings() {
        Document html = render("## Before\n\n# First title\n\n## Middle\n\n# Second title\n\n## After",
            true, true);
        assertThat(html.select(".toc")).hasSize(1);
        assertThat(html.select("h2").eachText()).containsExactly("1. Before", "2. Middle", "3. After");
        assertThat(html.selectFirst("h1").nextElementSibling().hasClass("toc")).isTrue();
        assertTocTargets(html, 3);
    }

    @Test
    void doesNotCreateAnEmptyTocAndResetsStateForEachDocument() {
        assertThat(render("# Only title", true, true).select(".toc")).isEmpty();
        for (int i = 0; i < 3; i++) {
            Document html = render("## Chapter", true, true);
            assertThat(html.selectFirst("h2").text()).isEqualTo("1. Chapter");
            assertTocTargets(html, 1);
        }
    }

    @Test
    void escapesTocLabelsWithoutTurningLiteralTextIntoHtml() {
        Document html = render("## `<img src=x>` &amp; \\*literal\\* [Label](https://example.com)",
            true, false);
        assertThat(html.selectFirst(".toc a").text()).isEqualTo("<img src=x> & *literal* Label");
        assertThat(html.select(".toc img, .toc script")).isEmpty();
        assertThat(html.selectFirst("h2 code").text()).isEqualTo("<img src=x>");
        assertTocTargets(html, 1);
    }

    @ParameterizedTest
    @MethodSource("escapedLabels")
    void keepsTocLabelsConsistentWithRenderedEntitiesAndLiteralCode(String source, String expected) {
        Document html = render("## " + source, true, false);
        assertThat(html.selectFirst("h2").text()).isEqualTo(expected);
        assertThat(html.selectFirst(".toc a").text()).isEqualTo(expected);
        assertThat(html.selectFirst(".toc a").children()).isEmpty();
        assertTocTargets(html, 1);
    }

    private static Stream<Arguments> escapedLabels() {
        return Stream.of(
            Arguments.of("&amp; and \\*literal\\*", "& and *literal*"),
            Arguments.of("`&amp;` and &amp;amp;", "&amp; and &amp;"),
            Arguments.of("<em>HTML</em> &lt;tag&gt; &#x26;", "HTML <tag> &")
        );
    }

    private Document render(String markdown, boolean toc, boolean numbers) {
        return Jsoup.parseBodyFragment(service.toHtml(markdown, toc, numbers));
    }

    private static void assertTocTargets(Document html, int count) {
        assertThat(html.select(".toc a")).hasSize(count);
        for (Element link : html.select(".toc a")) {
            String id = link.attr("href").substring(1);
            assertThat(html.select("[id]").stream().filter(node -> id.equals(node.id())).toList())
                .as("unique target for %s", link.attr("href")).hasSize(1);
            assertThat(html.getElementById(id).tagName()).matches("h[2-6]");
        }
    }
}
