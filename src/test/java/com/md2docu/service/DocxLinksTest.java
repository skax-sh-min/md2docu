package com.md2docu.service;

import com.md2docu.TestImages;
import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertWarning;
import com.md2docu.model.UserSettings;
import com.md2docu.util.ImageResolver;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRelation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Node;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocxLinksTest {
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private final ConvertOptions options = new ConvertOptions();
    private final List<ConvertWarning> warnings = new ArrayList<>();
    private final DocxConverter converter;

    DocxLinksTest() {
        var settings = mock(UserSettingsService.class);
        when(settings.get()).thenReturn(new UserSettings());
        converter = new DocxConverter(new ImageResolver(), settings);
    }

    @ParameterizedTest
    @CsvSource({"keep,local", "warn,local", "ignore,local", "keep,external", "warn,external",
        "ignore,external", "keep,anchor", "warn,anchor", "ignore,anchor"})
    void preservesOrderedFormattedTextBreaksAndImagesForEveryStrategy(String strategy, String kind) throws Exception {
        options.setLinkStrategy(strategy);
        String href = switch (kind) {
            case "local" -> "files/Notes%20%26.txt";
            case "anchor" -> "#chapter%26part";
            default -> "https://example.com/notes?one=1&amp;two=2";
        };
        boolean attachmentWarning = strategy.equals("warn") && kind.equals("local");
        boolean linked = !strategy.equals("ignore") && !attachmentWarning;
        byte[] expectedImage = TestImages.bytes(TestImages.Format.PNG);
        try (var doc = convert("<p>Before <a href='" + href + "'><strong>Bold</strong> <em>Italic</em> "
            + "<del>Old</del> <code>Code</code><br><img src='" + image() + "'> tail</a> After</p>"
            + "<h2 id='chapter&amp;part'>Destination</h2>")) {
            var paragraph = doc.getParagraphs().get(0);
            assertThat(visible(paragraph.getCTP().getDomNode())).isEqualTo(
                "Before Bold Italic Old Code\n[IMG] tail"
                    + (attachmentWarning ? " [⚠ 첨부파일 미포함: files/Notes%20%26.txt]" : "") + " After");
            assertThat(doc.getAllPictures()).singleElement()
                .satisfies(picture -> assertThat(picture.getData()).isEqualTo(expectedImage));
            assertThat(paragraph.getRuns()).anySatisfy(run -> {
                assertThat(run.text()).isEqualTo("Bold");
                assertThat(run.isBold()).isTrue();
            }).anySatisfy(run -> {
                assertThat(run.text()).isEqualTo("Italic");
                assertThat(run.isItalic()).isTrue();
            }).anySatisfy(run -> {
                assertThat(run.text()).isEqualTo("Old");
                assertThat(run.isStrikeThrough()).isTrue();
            }).anySatisfy(run -> {
                assertThat(run.text()).isEqualTo("Code");
                assertThat(run.getFontFamily()).isEqualTo("Courier New");
            });
            assertThat(paragraph.getCTP().getHyperlinkList()).hasSize(linked ? 1 : 0);
            if (linked) {
                var link = paragraph.getCTP().getHyperlinkArray(0);
                assertThat(visible(link.getDomNode())).isEqualTo("Bold Italic Old Code\n[IMG] tail");
                if ("anchor".equals(kind)) {
                    assertThat(link.getAnchor()).isEqualTo(doc.getParagraphs().get(1).getCTP().getBookmarkStartArray(0).getName());
                } else {
                    assertThat(doc.getPackagePart().getRelationship(link.getId()).getTargetURI().toString())
                        .isEqualTo(href.replace("&amp;", "&"));
                }
            }
        }
        if (attachmentWarning) assertThat(warnings).singleElement().extracting(ConvertWarning::getType).isEqualTo("ATTACHMENT_NOT_FOUND");
        else assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"keep", "warn", "ignore"})
    void honorsTheImageOptionInsideLinksWithoutLosingTheirText(String strategy) throws Exception {
        options.setLinkStrategy(strategy);
        options.setIncludeImages(false);
        try (var doc = convert("<p><a href='https://example.com'>Before<img src='" + image() + "'>After</a></p>")) {
            assertThat(doc.getAllPictures()).isEmpty();
            assertThat(doc.getParagraphs().get(0).getText()).isEqualTo("BeforeAfter");
        }
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "HTTP://example.com", "mailto:team@example.com", "ftp://example.com/Notes.txt",
        "files/ReadMe.md", "../Notes%20One.pdf", "/files/ReadMe.pdf"})
    void preservesSupportedTargetsWithoutFetchingOrAttachingThem(String href) throws Exception {
        options.setLinkStrategy("warn");
        boolean local = !href.contains(":");
        if (local) options.setLinkStrategy("keep");
        try (var doc = convert("<p><a href='" + href + "'>Link</a></p>")) {
            var link = doc.getParagraphs().get(0).getCTP().getHyperlinkArray(0);
            var relationship = doc.getPackagePart().getRelationship(link.getId());
            assertThat(relationship.getTargetURI().toString()).isEqualTo(href);
            assertThat(relationship.getRelationshipType()).isEqualTo(XWPFRelation.HYPERLINK.getRelation());
            assertThat(relationship.getTargetMode()).isEqualTo(org.apache.poi.openxml4j.opc.TargetMode.EXTERNAL);
            assertThat(doc.getAllPictures()).isEmpty();
            assertThat(doc.getPackage().getParts()).noneSatisfy(part ->
                assertThat(part.getPartName().getName()).contains("/embeddings/"));
        }
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "file:///C:/secret.txt", "jar:file:///app.jar!/notes",
        "data:text/html;base64,SGVsbG8=", "//example.com/file", "https://user:password@example.com/file", "bad%ZZ"})
    void rejectsUnsupportedTargetsButKeepsTheirInlineContent(String href) throws Exception {
        try (var doc = convert("<p><a href='" + href + "'><strong>Label</strong><img src='" + image() + "'></a></p>")) {
            assertThat(visible(doc.getParagraphs().get(0).getCTP().getDomNode())).isEqualTo("Label[IMG]");
            assertThat(doc.getParagraphs().get(0).getCTP().getHyperlinkList()).isEmpty();
            assertThat(doc.getAllPictures()).hasSize(1);
        }
        assertThat(warnings).singleElement().satisfies(warning -> {
            assertThat(warning.getType()).isEqualTo("UNSUPPORTED_RESOURCE");
            assertThat(warning.getTarget()).isEqualTo(href);
        });
    }

    @ParameterizedTest
    @MethodSource("anchorIds")
    void mapsForwardFragmentLinksToWordCompatibleBookmarks(String id) throws Exception {
        String encoded = java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        try (var doc = convert("<p><a href='#" + encoded + "'>Jump</a></p><h2 id='"
            + org.jsoup.nodes.Entities.escape(id) + "'>Destination</h2>")) {
            var target = doc.getParagraphs().get(1).getCTP();
            var bookmark = target.getBookmarkStartArray(0);
            assertThat(bookmark.getName()).matches("[A-Za-z][A-Za-z0-9_]{0,39}");
            assertThat(target.getBookmarkEndArray(0).getId()).isEqualTo(bookmark.getId());
            assertThat(doc.getParagraphs().get(0).getCTP().getHyperlinkArray(0).getAnchor()).isEqualTo(bookmark.getName());
        }
        assertThat(warnings).isEmpty();
    }

    static Stream<String> anchorIds() {
        return Stream.of("simple", "toc-1", "1 start", "chapter&part", "한글 제목", "x".repeat(80));
    }

    @Test
    void keepsBookmarkNamesUniqueIncludingCaseInsensitiveAndGeneratedNameCollisions() throws Exception {
        List<String> ids = List.of("toc-1", "toc_1", "md2docu_1", "Target", "target", "toc-1");
        StringBuilder html = new StringBuilder("<p><a href='#toc-1'>First</a><a href='#toc_1'>Second</a>"
            + "<a href='#Target'>Third</a><a href='#target'>Fourth</a></p>");
        for (int i = 0; i < ids.size(); i++) html.append("<h2 id='").append(ids.get(i)).append("'>Title ").append(i).append("</h2>");
        try (var doc = convert(html.toString())) {
            var bookmarks = doc.getParagraphs().stream().flatMap(p -> p.getCTP().getBookmarkStartList().stream()).toList();
            assertThat(bookmarks).hasSize(5);
            assertThat(bookmarks).extracting(b -> b.getName().toLowerCase(Locale.ROOT)).doesNotHaveDuplicates();
            assertThat(new HashSet<>(bookmarks.stream().map(b -> b.getId()).toList())).hasSize(5);
            var links = doc.getParagraphs().get(0).getCTP().getHyperlinkList();
            assertThat(links.get(0).getAnchor()).isEqualTo(doc.getParagraphs().get(1).getCTP().getBookmarkStartArray(0).getName());
            assertThat(links.get(1).getAnchor()).isEqualTo("toc_1");
            assertThat(links.get(2).getAnchor()).isEqualTo("Target");
            assertThat(links.get(3).getAnchor()).isNotEqualToIgnoringCase("Target");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"<a name='named'></a>", "<span id='named'></span>",
        "<a id='named' href='files/notes.txt'><strong>Attachment</strong></a>"})
    void preservesNamedTargetsEvenWhenTheirOwnHyperlinkIsRemoved(String target) throws Exception {
        options.setLinkStrategy("warn");
        try (var doc = convert("<p><a href='#named'>Jump</a></p><p>" + target + "Destination</p>")) {
            assertThat(doc.getParagraphs().get(0).getCTP().getHyperlinkArray(0).getAnchor()).isEqualTo("named");
            assertThat(doc.getParagraphs().get(1).getCTP().getBookmarkStartArray(0).getName()).isEqualTo("named");
        }
        assertThat(warnings).hasSize(target.contains("href=") ? 1 : 0);
    }

    @Test
    void removesUnresolvedAnchorLinksAndWarnsWithoutLosingContent() throws Exception {
        try (var doc = convert("<p><a href='#missing'><em>Missing</em><img src='" + image() + "'></a></p>")) {
            assertThat(doc.getParagraphs().get(0).getCTP().getHyperlinkList()).isEmpty();
            assertThat(visible(doc.getParagraphs().get(0).getCTP().getDomNode())).isEqualTo("Missing[IMG]");
        }
        assertThat(warnings).singleElement().extracting(ConvertWarning::getType).isEqualTo("HYPERLINK_UNRESOLVED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"heading", "list", "quote", "table", "bare"})
    void preservesLinkedContentInEveryRenderingContext(String context) throws Exception {
        String link = "<a href='https://example.com'><strong>Label</strong><img src='" + image() + "'>Tail</a>";
        String html = switch (context) {
            case "heading" -> "<h2>" + link + "</h2>";
            case "list" -> "<ul><li>" + link + "</li></ul>";
            case "quote" -> "<blockquote>" + link + "</blockquote>";
            case "table" -> "<table><tr><th>" + link + "</th></tr></table>";
            default -> link;
        };
        for (String strategy : List.of("keep", "warn", "ignore")) {
            options.setLinkStrategy(strategy);
            try (var doc = convert(html)) {
                var paragraph = context.equals("table")
                    ? doc.getTables().get(0).getRow(0).getCell(0).getParagraphs().get(0) : doc.getParagraphs().get(0);
                assertThat(visible(paragraph.getCTP().getDomNode())).isEqualTo("Label[IMG]Tail");
                assertThat(doc.getAllPictures()).hasSize(1);
                assertThat(paragraph.getCTP().getHyperlinkList()).hasSize(strategy.equals("ignore") ? 0 : 1);
                assertThat(paragraph.getRuns()).anySatisfy(run -> {
                    assertThat(run.text()).isEqualTo("Label");
                    assertThat(run.isBold()).isTrue();
                    if (context.equals("heading")) assertThat(run.getFontSizeAsDouble()).isEqualTo(20d);
                });
            }
        }
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"keep", "warn", "ignore"})
    void preservesFailedImagePlaceholdersAndReportsExactlyOneImageWarning(String strategy) throws Exception {
        options.setLinkStrategy(strategy);
        try (var doc = convert("<p><a href='https://example.com'><em>Before</em>"
            + "<img src='data:image/png;base64,%%=='>After</a></p>")) {
            assertThat(doc.getParagraphs().get(0).getText()).contains("Before", "[이미지 로드 실패:", "After");
            assertThat(doc.getAllPictures()).isEmpty();
        }
        assertThat(warnings).singleElement().extracting(ConvertWarning::getType).isEqualTo("IMAGE_INVALID_DATA");
    }

    @Test
    void preservesRepeatedLinkedImagesWithSharedMediaAndSeparateDisplayPositions() throws Exception {
        try (var doc = convert("<p><a href='files/Notes.txt'><img src='" + image() + "'>Middle"
            + "<img src='" + image() + "'></a></p>")) {
            var link = doc.getParagraphs().get(0).getCTP().getHyperlinkArray(0);
            assertThat(visible(link.getDomNode())).isEqualTo("[IMG]Middle[IMG]");
            assertThat(doc.getAllPictures()).hasSize(1);
            assertThat(doc.getParagraphs().get(0).getRuns().stream().flatMap(run -> run.getEmbeddedPictures().stream()).count())
                .isEqualTo(2);
        }
    }

    @Test
    void doesNotLeaveLinksToTargetsThatWereNotActuallyRendered() throws Exception {
        try (var doc = convert("<p><a href='#column'><strong>Jump</strong><img src='" + image() + "'></a></p>"
            + "<table><colgroup><col id='column'></colgroup><tr><td>Cell</td></tr></table>")) {
            var paragraph = doc.getParagraphs().get(0);
            assertThat(paragraph.getCTP().getHyperlinkList()).isEmpty();
            assertThat(visible(paragraph.getCTP().getDomNode())).isEqualTo("Jump[IMG]");
            assertThat(paragraph.getRuns().get(0).isBold()).isTrue();
            assertThat(paragraph.getRuns().get(0).getUnderline()).isEqualTo(org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE);
        }
        assertThat(warnings).singleElement().extracting(ConvertWarning::getType).isEqualTo("HYPERLINK_UNRESOLVED");
    }

    @Test
    void writesBookmarksAtRenderedParagraphsListsTablesAndCode() throws Exception {
        String targets = "<div id='container'><p id='paragraph'>Paragraph</p></div>"
            + "<ul id='list'><li id='item'><p id='list-paragraph'>Item</p></li></ul>"
            + "<table id='table'><tbody id='rows'><tr id='row'><td id='cell'>Cell</td></tr></tbody></table>"
            + "<pre id='pre'><code id='code'>Code</code></pre>";
        List<String> ids = List.of("container", "paragraph", "list", "item", "list-paragraph", "table", "rows", "row", "cell", "pre", "code");
        String links = ids.stream().map(id -> "<a href='#" + id + "'>Jump</a>").collect(java.util.stream.Collectors.joining());
        try (var doc = convert("<p>" + links + "</p>" + targets)) {
            List<String> names = new ArrayList<>();
            doc.getParagraphs().forEach(p -> p.getCTP().getBookmarkStartList().forEach(b -> names.add(b.getName())));
            doc.getTables().forEach(t -> t.getRows().forEach(r -> r.getTableCells().forEach(c ->
                c.getParagraphs().forEach(p -> p.getCTP().getBookmarkStartList().forEach(b -> names.add(b.getName()))))));
            assertThat(names).hasSize(ids.size()).doesNotHaveDuplicates();
            assertThat(doc.getParagraphs().get(0).getCTP().getHyperlinkList()).allSatisfy(link ->
                assertThat(names).contains(link.getAnchor()));
        }
        assertThat(warnings).isEmpty();
    }

    @Test
    void supportsBareNamedAnchorsWithoutLosingTheirFollowingContent() throws Exception {
        try (var doc = convert("<p><a href='#named'>Jump</a></p><a name='named'></a><p>Destination</p>")) {
            assertThat(doc.getParagraphs().get(1).getCTP().getBookmarkStartArray(0).getName()).isEqualTo("named");
            assertThat(doc.getParagraphs().get(2).getText()).isEqualTo("Destination");
        }
        assertThat(warnings).isEmpty();
    }

    @Test
    void keepsLooseListParagraphBoundariesAfterLinkedOnlyContent() throws Exception {
        try (var doc = convert("<ul><li><a href='https://example.com'>First</a><p>Second</p></li></ul>")) {
            assertThat(doc.getParagraphs()).extracting(p -> p.getText()).containsExactly("First", "Second");
            assertThat(doc.getParagraphs().get(1).getNumID()).isNull();
        }
    }

    @Test
    void mapsIdAndNameAliasesToDistinctPairedBookmarksAtTheSamePosition() throws Exception {
        try (var doc = convert("<p><a href='#first'>One</a><a href='#second'>Two</a></p>"
            + "<p><a id='first' name='second'></a>Destination</p>")) {
            var target = doc.getParagraphs().get(1).getCTP();
            assertThat(target.getBookmarkStartList()).extracting(b -> b.getName()).containsExactly("first", "second");
            assertThat(target.getBookmarkEndList()).extracting(b -> b.getId())
                .containsExactlyElementsOf(target.getBookmarkStartList().stream().map(b -> b.getId()).toList());
        }
    }

    @Test
    void ignoresEvenUnsupportedOrUnresolvedTargetsWithoutWarningOrContentLoss() throws Exception {
        options.setLinkStrategy("ignore");
        try (var doc = convert("<p><a href='javascript:alert(1)'><strong>Unsafe</strong><img src='" + image()
            + "'></a><a href='#missing'><em>Missing</em></a></p>")) {
            assertThat(visible(doc.getParagraphs().get(0).getCTP().getDomNode())).isEqualTo("Unsafe[IMG]Missing");
            assertThat(doc.getParagraphs().get(0).getCTP().getHyperlinkList()).isEmpty();
        }
        assertThat(warnings).isEmpty();
    }

    private XWPFDocument convert(String html) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(converter.convert(html, options, null, warnings)));
    }

    private String image() throws Exception {
        return TestImages.dataUri(TestImages.bytes(TestImages.Format.PNG));
    }

    private String visible(Node node) {
        if (node.getNodeType() == Node.TEXT_NODE) return node.getNodeValue();
        if (WORD_NS.equals(node.getNamespaceURI())) {
            if ("drawing".equals(node.getLocalName())) return "[IMG]";
            if ("br".equals(node.getLocalName())) return "\n";
        }
        StringBuilder result = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) result.append(visible(child));
        return result.toString();
    }
}
