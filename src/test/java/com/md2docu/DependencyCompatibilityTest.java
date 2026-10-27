package com.md2docu;

import com.md2docu.model.UserSettings;
import com.md2docu.service.UserSettingsService;
import com.md2docu.util.ZipExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRelation;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.pdf.font.paths=")
@AutoConfigureMockMvc
class DependencyCompatibilityTest {

    private static final String DOCX_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoSpyBean
    private UserSettingsService settingsService;

    @MockitoSpyBean
    private ZipExtractor zipExtractor;

    @BeforeEach
    void useInMemorySettings() {
        doReturn(new UserSettings()).when(settingsService).get();
    }

    @Test
    void rendersThymeleafPageWithMatchingBootstrapAssets() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("bootstrap@5.3.8/dist/css/bootstrap.min.css")))
            .andExpect(content().string(containsString("bootstrap@5.3.8/dist/js/bootstrap.bundle.min.js")))
            .andExpect(content().string(containsString("aria-describedby=\"preview-status preview-help\"")))
            .andExpect(content().string(containsString("id=\"preview-status\"")))
            .andExpect(content().string(containsString("입력 텍스트와 소제목 번호 옵션만 반영")));
    }

    @Test
    void previewsMarkdownThroughJacksonAndSpringMvc() throws Exception {
        mvc.perform(post("/api/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("markdown", "# Hello\n\n**world**"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.html", containsString("<strong>world</strong>")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void previewsFormattedHeadingsWithIndependentNumbering(boolean numbers) throws Exception {
        MvcResult result = mvc.perform(post("/api/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of(
                    "markdown", "**Chapter** `code`\n------------------\n\n#### Jump",
                    "numberHeadings", numbers
                ))))
            .andExpect(status().isOk())
            .andReturn();
        var html = Jsoup.parseBodyFragment(
            mapper.readTree(result.getResponse().getContentAsByteArray()).path("html").asText());
        assertThat(html.select(".toc")).isEmpty();
        assertThat(html.selectFirst("h2").text()).isEqualTo((numbers ? "1. " : "") + "Chapter code");
        assertThat(html.selectFirst("h2 strong").text()).isEqualTo("Chapter");
        assertThat(html.selectFirst("h2 code").text()).isEqualTo("code");
        assertThat(html.selectFirst("h4").text()).isEqualTo((numbers ? "1.1.1. " : "") + "Jump");
    }

    @ParameterizedTest
    @MethodSource("headingOptions")
    void convertsFormattedHeadingsWithMatchingTocTargets(
            String format, boolean hasH1, boolean toc, boolean numbers) throws Exception {
        String markdown = (hasH1 ? "# Document\n\n" : "")
            + "**Bold** and `code`\n------------------\n\nBody.\n\n### Child";
        ConversionResponse response = response(mvc.perform(post("/api/convert/" + format + "/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of(
                    "markdown", markdown, "generateToc", toc, "numberHeadings", numbers
                ))))
            .andExpect(status().isOk())
            .andReturn());
        assertThat(response.warnings()).isEmpty();
        String first = (numbers ? "1. " : "") + "Bold and code";
        String child = (numbers ? "1.1. " : "") + "Child";
        if ("pdf".equals(format)) {
            try (var pdf = Loader.loadPDF(download(response, MediaType.APPLICATION_PDF_VALUE))) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                List<String> lines = stripper.getText(pdf).lines()
                    .map(line -> line.replace('\u00a0', ' ').strip()).toList();
                assertThat(lines.stream().filter(first::equals).count())
                    .as("heading and TOC label in %s", lines).isEqualTo(toc ? 2 : 1);
                assertThat(lines.stream().filter(child::equals).count())
                    .as("child heading and TOC label in %s", lines).isEqualTo(toc ? 2 : 1);
                List<PDActionGoTo> destinations = new ArrayList<>();
                for (var page : pdf.getPages()) {
                    for (var annotation : page.getAnnotations()) {
                        if (annotation instanceof PDAnnotationLink link
                                && link.getAction() instanceof PDActionGoTo goTo) {
                            assertThat(goTo.getDestination()).isNotNull();
                            destinations.add(goTo);
                        }
                    }
                }
                assertThat(destinations).hasSize(toc ? 2 : 0);
            }
        } else {
            try (XWPFDocument doc = new XWPFDocument(
                    new ByteArrayInputStream(download(response, DOCX_CONTENT_TYPE)))) {
                List<String> paragraphs = doc.getParagraphs().stream().map(p -> p.getText()).toList();
                assertThat(paragraphs).contains(first, child);
                assertThat(String.join("\n", paragraphs)).doesNotContain("**Bold**", "`code`");
                var links = doc.getParagraphs().stream()
                    .flatMap(p -> p.getCTP().getHyperlinkList().stream()).toList();
                assertThat(links).hasSize(toc ? 2 : 0);
                if (toc) {
                    var headings = doc.getParagraphs().stream()
                        .filter(p -> "Heading2".equals(p.getStyleID()) || "Heading3".equals(p.getStyleID())).toList();
                    assertThat(headings).extracting(p -> p.getText()).containsExactly(first, child);
                    var bookmarks = headings.stream()
                        .flatMap(p -> p.getCTP().getBookmarkStartList().stream()).toList();
                    assertThat(links).extracting(link -> link.getAnchor())
                        .containsExactlyElementsOf(bookmarks.stream().map(b -> b.getName()).toList())
                        .doesNotHaveDuplicates();
                }
            }
        }
    }

    private static Stream<Arguments> headingOptions() {
        return Stream.of("pdf", "docx").flatMap(format ->
            Stream.of(false, true).flatMap(h1 -> Stream.of(false, true).flatMap(toc ->
                Stream.of(false, true).map(numbers -> Arguments.of(format, h1, toc, numbers)))));
    }

    @Test
    void convertsAndDownloadsDocxWithTextTableAndHyperlink() throws Exception {
        String markdown = "# Compatibility\n\nHello **DOCX**.\n\n"
            + "[Site](https://example.com)\n\n"
            + "| Key | Value |\n| --- | --- |\n| A | B |\n";
        ConversionResponse response = convertText("docx", markdown);
        assertThat(response.fileName()).isEqualTo("document.docx");
        assertThat(response.warnings()).isEmpty();

        byte[] bytes = download(response, DOCX_CONTENT_TYPE);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = doc.getParagraphs().stream()
                .map(p -> p.getText())
                .collect(Collectors.joining("\n"));
            assertThat(text).contains("Compatibility", "Hello DOCX.", "Site");
            assertThat(doc.getTables()).hasSize(1);
            assertThat(doc.getTables().get(0).getText()).contains("Key", "Value", "A", "B");
            assertThat(doc.getPackagePart()
                .getRelationshipsByType(XWPFRelation.HYPERLINK.getRelation()).size()).isEqualTo(1);
        }
    }

    @Test
    void convertsAndDownloadsReadablePdf() throws Exception {
        ConversionResponse response = convertText("pdf", "# Compatibility\n\nHello **PDF**.");
        assertThat(response.fileName()).isEqualTo("document.pdf");
        assertThat(response.warnings()).isEmpty();

        byte[] bytes = download(response, MediaType.APPLICATION_PDF_VALUE);
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            assertThat(stripper.getText(pdf)).contains("Compatibility", "Hello PDF.");
        }
    }

    @Test
    void embedsLocalImageFromRootZipDocument() throws Exception {
        byte[] image = png();
        byte[] archive = zip(Map.of(
            "document.md", "![image](images/test.png)".getBytes(StandardCharsets.UTF_8),
            "images/test.png", image
        ));
        MockMultipartFile file = new MockMultipartFile("file", "document.zip", "application/zip", archive);
        MvcResult result = mvc.perform(multipart("/api/convert/docx").file(file))
            .andExpect(status().isOk())
            .andReturn();
        ConversionResponse response = response(result);
        assertThat(response.warnings()).isEmpty();

        try (XWPFDocument doc = new XWPFDocument(
                new ByteArrayInputStream(download(response, DOCX_CONTENT_TYPE)))) {
            assertThat(doc.getAllPictures()).hasSize(1);
            assertThat(doc.getAllPictures().get(0).getData()).isEqualTo(image);
            assertThat(doc.getAllPictures().get(0).getPictureType()).isEqualTo(Document.PICTURE_TYPE_PNG);
        }
    }

    @Test
    void convertsZipToPdfWithImageTocAndAttachmentWarning() throws Exception {
        String markdown = "# Archive\n\n![image](images/test.png)\n\n"
            + "[Attachment](files/notes.txt)\n\n## Chapter\n\nChapter body.";
        byte[] archive = zip(Map.of(
            "document.md", markdown.getBytes(StandardCharsets.UTF_8),
            "images/test.png", png()
        ));
        MockMultipartFile file = new MockMultipartFile("file", "document.zip", "application/zip", archive);
        ConversionResponse response = response(mvc.perform(multipart("/api/convert/pdf")
                .file(file).param("generateToc", "true").param("linkStrategy", "warn"))
            .andExpect(status().isOk())
            .andReturn());
        assertThat(response.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.get("type")).isEqualTo("ATTACHMENT_NOT_FOUND");
            assertThat(warning.get("target")).isEqualTo("files/notes.txt");
        });

        try (var pdf = Loader.loadPDF(download(response, MediaType.APPLICATION_PDF_VALUE))) {
            var page = pdf.getPage(0);
            List<PDImageXObject> images = new ArrayList<>();
            for (var name : page.getResources().getXObjectNames()) {
                if (page.getResources().getXObject(name) instanceof PDImageXObject image) {
                    images.add(image);
                }
            }
            assertThat(images).hasSize(1);
            assertThat(images.getFirst().getImage().getWidth()).isEqualTo(2);
            assertThat(images.getFirst().getImage().getHeight()).isEqualTo(2);
            assertThat(page.getAnnotations()).anyMatch(annotation ->
                annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionGoTo);
        }
    }

    @ParameterizedTest
    @MethodSource("zipImagePaths")
    void resolvesZipImagesAgainstTheDocumentAndArchiveRoot(
            String format, String document, String reference, String imagePath) throws Exception {
        byte[] image = png();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String parent = document.contains("/") ? document.substring(0, document.lastIndexOf('/') + 1) : "";
        entries.put(document, ("# Document\n\n![image](" + reference + ")").getBytes(StandardCharsets.UTF_8));
        entries.put("images/test.png", png(0xCC3344));
        entries.put(parent + "images/test.png", png(0xCC3344));
        entries.put(imagePath, image);
        ConversionResponse response = convertArchive(format, entries);
        assertThat(response.warnings()).isEmpty();
        assertArchiveImage(response, format, image);
        assertArchiveCleanedUp();
    }

    private static Stream<Arguments> zipImagePaths() {
        List<String[]> paths = List.of(
            new String[] {"document.md", "images/test.png", "images/test.png"},
            new String[] {"document.md", "/images/test.png", "images/test.png"},
            new String[] {"docs/guide.md", "images/test.png", "docs/images/test.png"},
            new String[] {"docs/guide.md", "./images/test.png", "docs/images/test.png"},
            new String[] {"docs/guide.md", "../images/test.png", "images/test.png"},
            new String[] {"docs/guide.md", "/images/test.png", "images/test.png"},
            new String[] {"docs/chapters/guide.md", "images/test.png", "docs/chapters/images/test.png"},
            new String[] {"docs/chapters/guide.md", "../../images/test.png", "images/test.png"},
            new String[] {"docs/chapters/guide.md", "/images/test.png", "images/test.png"},
            new String[] {"docs/guide.md", "images/test%20%26.png", "docs/images/test &.png"},
            new String[] {"docs/guide.md", "images/test%2520%26.png", "docs/images/test%20&.png"},
            new String[] {"docs/guide.md", "images/test.png?v=1&size=2", "docs/images/test.png"}
        );
        return Stream.of("pdf", "docx").flatMap(format ->
            paths.stream().map(path -> Arguments.of(format, path[0], path[1], path[2])));
    }

    @ParameterizedTest
    @MethodSource("zipImageContainers")
    void resolvesNestedZipImagesInsideDifferentContainers(String format, String markdown) throws Exception {
        byte[] image = png();
        ConversionResponse response = convertArchive(format, Map.of(
            "docs/guide.md", markdown.getBytes(StandardCharsets.UTF_8),
            "docs/images/test.png", image
        ));
        assertThat(response.warnings()).isEmpty();
        assertArchiveImage(response, format, image);
        assertArchiveCleanedUp();
    }

    private static Stream<Arguments> zipImageContainers() {
        return Stream.of("pdf", "docx").flatMap(format -> Stream.of(
            "Text ![image](images/test.png) after.",
            "- ![image](images/test.png)",
            "> ![image](images/test.png)",
            "| Image |\n| --- |\n| ![image](images/test.png) |"
        ).map(markdown -> Arguments.of(format, markdown)));
    }

    @ParameterizedTest
    @MethodSource("zipMissingImages")
    void warnsAndCleansUpZipFilesWithInvalidOrMissingImages(
            String format, String reference) throws Exception {
        ConversionResponse response = convertArchive(format, Map.of(
            "docs/guide.md", ("![image](" + reference + ")").getBytes(StandardCharsets.UTF_8)
        ));
        assertThat(response.warnings()).singleElement().satisfies(warning ->
            assertThat(warning.get("type")).isEqualTo("IMAGE_NOT_FOUND"));
        assertArchiveCleanedUp();
    }

    private static Stream<Arguments> zipMissingImages() {
        return Stream.of("pdf", "docx").flatMap(format -> Stream.of(
            "missing.png", "../../outside.png", "/../outside.png", "%2e%2e/%2e%2e/outside.png"
        ).map(reference -> Arguments.of(format, reference)));
    }

    @Test
    void cleansUpAnArchiveWhenConversionFails() throws Exception {
        doThrow(new UncheckedIOException("Settings could not be loaded", new IOException("test failure")))
            .when(settingsService).get();
        MockMultipartFile file = new MockMultipartFile("file", "document.zip", "application/zip",
            zip(Map.of("docs/guide.md", "Text only".getBytes(StandardCharsets.UTF_8))));

        mvc.perform(multipart("/api/convert/pdf").file(file))
            .andExpect(status().isInternalServerError());
        assertArchiveCleanedUp();
    }

    @ParameterizedTest
    @MethodSource("archiveFormats")
    void cleansUpArchivesWithoutImages(String format) throws Exception {
        ConversionResponse response = convertArchive(format, Map.of(
            "docs/guide.md", "# Text only\n\nNo images.".getBytes(StandardCharsets.UTF_8)
        ));
        assertThat(response.warnings()).isEmpty();
        assertThat(download(response, "docx".equals(format) ? DOCX_CONTENT_TYPE : MediaType.APPLICATION_PDF_VALUE))
            .isNotEmpty();
        assertArchiveCleanedUp();
    }

    private static Stream<String> archiveFormats() {
        return Stream.of("pdf", "docx");
    }

    @Test
    void cleansUpArchivesWithoutMarkdown() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "document.zip", "application/zip",
            zip(Map.of("images/test.png", png())));

        mvc.perform(multipart("/api/convert/pdf").file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").isString());
        assertArchiveCleanedUp();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "docx"})
    void convertsSvgAndWebpAndReturnsTheSameImageWarningThroughBothApis(String format) throws Exception {
        String invalid = "data:image/png;base64,invalid-base64";
        String markdown = "![svg](" + TestImages.dataUri(TestImages.bytes(TestImages.Format.SVG))
            + ")\n\n![webp](" + TestImages.dataUri(TestImages.bytes(TestImages.Format.WEBP))
            + ")\n\n![invalid](" + invalid + ")";
        ConversionResponse response = response(mvc.perform(post("/api/convert/" + format + "/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("markdown", markdown))))
            .andExpect(status().isOk()).andReturn());
        assertThat(response.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.get("type")).isEqualTo("IMAGE_INVALID_DATA");
            assertThat(warning.get("target")).isEqualTo(invalid);
        });
        mvc.perform(get("/api/convert/" + response.jobId() + "/warnings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].type").value("IMAGE_INVALID_DATA"))
            .andExpect(jsonPath("$[0].target").value(invalid));
        if ("docx".equals(format)) {
            try (var document = new XWPFDocument(new ByteArrayInputStream(download(response, DOCX_CONTENT_TYPE)))) {
                assertThat(document.getParagraphs().stream().flatMap(p -> p.getRuns().stream())
                    .mapToInt(r -> r.getEmbeddedPictures().size()).sum()).isEqualTo(2);
                assertThat(document.getAllPictures()).allSatisfy(p ->
                    assertThat(p.getPackagePart().getContentType()).isEqualTo("image/png"));
            }
        } else {
            try (var document = Loader.loadPDF(download(response, MediaType.APPLICATION_PDF_VALUE))) {
                List<PDImageXObject> images = new ArrayList<>();
                for (var page : document.getPages()) {
                    for (var name : page.getResources().getXObjectNames()) {
                        if (page.getResources().getXObject(name) instanceof PDImageXObject image) images.add(image);
                    }
                }
                assertThat(images).hasSizeBetween(1, 2).allSatisfy(image -> {
                    assertThat(image.getWidth()).isEqualTo(16);
                    assertThat(image.getHeight()).isEqualTo(10);
                });
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"pdf,A4", "docx,A4", "pdf,LETTER", "docx,LETTER"})
    void reportsIncompatibleLegacyMarginsAsJsonForTextAndFileConversions(String format, String paper) throws Exception {
        UserSettings legacy = new UserSettings();
        legacy.setMarginLeftMm(25);
        legacy.setMarginRightMm(25);
        doReturn(legacy).when(settingsService).get();
        mvc.perform(post("/api/convert/" + format + "/text").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("markdown", "# Document", "pageSize", paper))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("180")))
            .andExpect(jsonPath("$.error", containsString("250")));
        var file = new MockMultipartFile("file", "document.md", "text/markdown",
            "# Document".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/convert/" + format).file(file).param("pageSize", paper))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("180")))
            .andExpect(jsonPath("$.error", containsString("250")));
    }

    private ConversionResponse convertArchive(String format, Map<String, byte[]> entries) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "document.zip", "application/zip", zip(entries));
        return response(mvc.perform(multipart("/api/convert/" + format).file(file))
            .andExpect(status().isOk())
            .andReturn());
    }

    private void assertArchiveCleanedUp() {
        ArgumentCaptor<Path> root = ArgumentCaptor.forClass(Path.class);
        verify(zipExtractor).cleanup(root.capture());
        assertThat(root.getValue()).doesNotExist();
    }

    private void assertArchiveImage(ConversionResponse response, String format, byte[] expected) throws Exception {
        if ("docx".equals(format)) {
            try (XWPFDocument doc = new XWPFDocument(
                    new ByteArrayInputStream(download(response, DOCX_CONTENT_TYPE)))) {
                assertThat(doc.getAllPictures()).hasSize(1);
                assertThat(doc.getAllPictures().getFirst().getData()).isEqualTo(expected);
            }
        } else {
            try (var pdf = Loader.loadPDF(download(response, MediaType.APPLICATION_PDF_VALUE))) {
                List<PDImageXObject> images = new ArrayList<>();
                for (var page : pdf.getPages()) {
                    for (var name : page.getResources().getXObjectNames()) {
                        if (page.getResources().getXObject(name) instanceof PDImageXObject image) images.add(image);
                    }
                }
                assertThat(images).hasSize(1);
                BufferedImage actual = images.getFirst().getImage();
                BufferedImage original = ImageIO.read(new ByteArrayInputStream(expected));
                assertThat(actual.getWidth()).isEqualTo(original.getWidth());
                assertThat(actual.getHeight()).isEqualTo(original.getHeight());
                for (int y = 0; y < original.getHeight(); y++) {
                    for (int x = 0; x < original.getWidth(); x++) {
                        assertThat(actual.getRGB(x, y)).isEqualTo(original.getRGB(x, y));
                    }
                }
            }
        }
    }

    @Test
    void convertsDocxHeadingFormattingAndTableToMarkdown() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            var heading = doc.createParagraph();
            heading.setStyle("Heading2");
            heading.createRun().setText("Chapter");
            var run = doc.createParagraph().createRun();
            run.setBold(true);
            run.setText("Bold text");
            var table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Key");
            table.getRow(0).getCell(1).setText("Value");
            table.getRow(1).getCell(0).setText("A");
            table.getRow(1).getCell(1).setText("B");
            bytes = documentBytes(doc);
        }
        MockMultipartFile file = new MockMultipartFile("file", "source.docx", DOCX_CONTENT_TYPE, bytes);
        ConversionResponse response = response(mvc.perform(multipart("/api/convert/md").file(file))
            .andExpect(status().isOk())
            .andReturn());

        assertThat(response.fileName()).isEqualTo("source.md");
        String markdown = new String(download(response, "text/markdown; charset=UTF-8"), StandardCharsets.UTF_8);
        assertThat(markdown).contains("## Chapter", "**Bold text**", "| Key | Value |", "| A | B |");
    }

    @Test
    void extractsDocxImagesIntoMarkdownZip() throws Exception {
        byte[] image = png();
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Image document");
            doc.createParagraph().createRun().addPicture(
                new ByteArrayInputStream(image), Document.PICTURE_TYPE_PNG,
                "test.png", Units.toEMU(2), Units.toEMU(2));
            bytes = documentBytes(doc);
        }
        MockMultipartFile file = new MockMultipartFile("file", "source.docx", DOCX_CONTENT_TYPE, bytes);
        ConversionResponse response = response(mvc.perform(multipart("/api/convert/md").file(file))
            .andExpect(status().isOk())
            .andReturn());

        assertThat(response.fileName()).isEqualTo("source.zip");
        Map<String, byte[]> entries = unzip(download(response, "application/zip"));
        assertThat(entries).containsOnlyKeys("source.md", "images/image_1.png");
        assertThat(new String(entries.get("source.md"), StandardCharsets.UTF_8))
            .contains("Image document", "images/image_1.png");
        assertThat(entries.get("images/image_1.png")).isEqualTo(image);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void downloadsMixedRunContentAndImagesWithoutLoss(boolean chapters) throws Exception {
        byte[] image = png();
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            var heading = doc.createParagraph();
            heading.setStyle("Heading2");
            heading.createRun().setText("Chapter");
            var run = doc.createParagraph().createRun();
            run.setText("LEFT *literal* ");
            run.addPicture(new ByteArrayInputStream(image), Document.PICTURE_TYPE_PNG,
                "test.png", Units.toEMU(2), Units.toEMU(2));
            run.addBreak();
            run.addTab();
            run.setText("RIGHT");
            bytes = documentBytes(doc);
        }
        var file = new MockMultipartFile("file", "mixed.docx", DOCX_CONTENT_TYPE, bytes);
        ConversionResponse response = response(mvc.perform(multipart("/api/convert/md")
                .file(file).param("splitByChapter", String.valueOf(chapters)))
            .andExpect(status().isOk()).andReturn());
        assertThat(response.warnings()).isEmpty();
        Map<String, byte[]> entries = unzip(download(response, "application/zip"));
        String path = chapters ? "01_Chapter.md" : "mixed.md";
        assertThat(entries).containsKeys(path, "images/image_1.png");
        assertThat(entries.get("images/image_1.png")).isEqualTo(image);
        String source = new String(entries.get(path), StandardCharsets.UTF_8);
        assertThat(source).contains("&#9;");
        var html = Jsoup.parseBodyFragment(new com.md2docu.service.MarkdownService().toHtml(source, false, false));
        assertThat(html.selectFirst("p").text()).contains("LEFT *literal*", "RIGHT");
        assertThat(html.select("p img").eachAttr("src")).containsExactly("images/image_1.png");
        assertThat(html.select("p br")).hasSize(1);
        assertThat(html.select("em,strong")).isEmpty();
    }

    @Test
    void exposesUnsupportedDocxContentWarningsWithoutDroppingFieldResults() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            var field = doc.createParagraph().getCTP().addNewFldSimple();
            field.setInstr("DATE");
            field.addNewR().addNewT().setStringValue("Visible result");
            bytes = documentBytes(doc);
        }
        var file = new MockMultipartFile("file", "field.docx", DOCX_CONTENT_TYPE, bytes);
        ConversionResponse response = response(mvc.perform(multipart("/api/convert/md").file(file))
            .andExpect(status().isOk()).andReturn());
        assertThat(response.warnings()).anySatisfy(warning ->
            assertThat(warning.get("type")).isEqualTo("UNSUPPORTED_DOCX_CONTENT"));
        assertThat(new String(download(response, "text/markdown; charset=UTF-8"), StandardCharsets.UTF_8))
            .contains("Visible result");
        mvc.perform(get("/api/convert/" + response.jobId() + "/warnings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].type").value("UNSUPPORTED_DOCX_CONTENT"));
    }

    @Test
    void buildsChapterCoverLinksFromPlainTitlesWithoutCreatingNestedLinks() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            var p = doc.createParagraph();
            p.setStyle("Heading2");
            p.createHyperlinkRun("https://example.com").setText("Link [label] *literal*");
            doc.createParagraph().createRun().setText("Body");
            bytes = documentBytes(doc);
        }
        var file = new MockMultipartFile("file", "linked.docx", DOCX_CONTENT_TYPE, bytes);
        ConversionResponse response = response(mvc.perform(multipart("/api/convert/md")
                .file(file).param("splitByChapter", "true"))
            .andExpect(status().isOk()).andReturn());
        assertThat(response.warnings()).isEmpty();
        Map<String, byte[]> entries = unzip(download(response, "application/zip"));
        assertThat(entries).hasSize(2);
        String cover = new String(entries.get("00_linked.md"), StandardCharsets.UTF_8);
        var html = Jsoup.parseBodyFragment(new com.md2docu.service.MarkdownService().toHtml(cover, false, false));
        assertThat(html.select("a")).hasSize(1);
        assertThat(html.selectFirst("a").text()).isEqualTo("Link [label] *literal*");
        assertThat(entries).containsKey(html.selectFirst("a").attr("href"));
        assertThat(html.select("em,strong")).isEmpty();
    }

    @Test
    void splitsDocxChaptersIntoZip() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            for (String title : List.of("First", "Second")) {
                var heading = doc.createParagraph();
                heading.setStyle("Heading2");
                heading.createRun().setText(title);
                doc.createParagraph().createRun().setText("Body of " + title);
            }
            bytes = documentBytes(doc);
        }
        MockMultipartFile file = new MockMultipartFile("file", "source.docx", DOCX_CONTENT_TYPE, bytes);
        ConversionResponse response = response(mvc.perform(multipart("/api/convert/md")
                .file(file).param("splitByChapter", "true"))
            .andExpect(status().isOk())
            .andReturn());

        Map<String, byte[]> entries = unzip(download(response, "application/zip"));
        assertThat(entries).containsOnlyKeys("00_source.md", "01_First.md", "02_Second.md");
        assertThat(new String(entries.get("00_source.md"), StandardCharsets.UTF_8))
            .contains("[First](01_First.md)", "[Second](02_Second.md)");
        assertThat(new String(entries.get("01_First.md"), StandardCharsets.UTF_8))
            .contains("## First", "Body of First")
            .doesNotContain("Body of Second");
    }

    @Test
    void splitsGeneratedDocxUsingItsSemanticHeadingsAndPreservesLists() throws Exception {
        ConversionResponse generated = convertText("docx",
            "# Title\n\n## First\n\n7. Parent\n   - Child\n\n## Second\n\nSecond body");
        byte[] bytes = download(generated, DOCX_CONTENT_TYPE);
        MockMultipartFile file = new MockMultipartFile("file", "generated.docx", DOCX_CONTENT_TYPE, bytes);
        ConversionResponse response = response(mvc.perform(multipart("/api/convert/md")
                .file(file).param("splitByChapter", "true"))
            .andExpect(status().isOk()).andReturn());
        assertThat(response.warnings()).isEmpty();
        Map<String, byte[]> entries = unzip(download(response, "application/zip"));
        assertThat(entries).containsOnlyKeys("00_generated.md", "01_First.md", "02_Second.md");
        String chapter = new String(entries.get("01_First.md"), StandardCharsets.UTF_8);
        assertThat(chapter).contains("## First", "7. Parent", "- Child").doesNotContain("Second body");
        var html = Jsoup.parseBodyFragment(new com.md2docu.service.MarkdownService().toHtml(chapter, false, false));
        assertThat(html.select("ol > li > ul > li").eachText()).containsExactly("Child");
    }

    @ParameterizedTest
    @CsvSource({"49,1,49,1", "49,2,49,1", "0,51,0,50", "50,1,50,0"})
    void splitsUnicodeChapterNamesOnCodePointBoundaries(int ascii, int emoji, int keptAscii, int keptEmoji) throws Exception {
        String title = "A".repeat(ascii) + "\uD83D\uDE00".repeat(emoji);
        byte[] bytes;
        try (var doc = new XWPFDocument()) {
            var heading = doc.createParagraph();
            heading.setStyle("Heading2");
            heading.createRun().setText(title);
            doc.createParagraph().createRun().setText("Body");
            bytes = documentBytes(doc);
        }
        var file = new MockMultipartFile("file", "unicode.docx", DOCX_CONTENT_TYPE, bytes);
        ConversionResponse response = response(mvc.perform(multipart("/api/convert/md")
                .file(file).param("splitByChapter", "true"))
            .andExpect(status().isOk()).andReturn());
        Map<String, byte[]> entries = unzip(download(response, "application/zip"));
        String chapter = "01_" + "A".repeat(keptAscii) + "\uD83D\uDE00".repeat(keptEmoji) + ".md";
        assertThat(entries).containsOnlyKeys("00_unicode.md", chapter);
        assertThat(new String(entries.get(chapter), StandardCharsets.UTF_8)).contains(title, "Body");
        String cover = new String(entries.get("00_unicode.md"), StandardCharsets.UTF_8);
        var html = Jsoup.parseBodyFragment(new com.md2docu.service.MarkdownService().toHtml(cover, false, false));
        assertThat(java.net.URI.create(html.selectFirst("a").attr("href")).getPath()).isEqualTo(chapter);
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void preservesSettingsJsonPropertyNames() throws Exception {
        mvc.perform(get("/api/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bodyFontSizePt").value(11))
            .andExpect(jsonPath("$.h1FontSizePt").value(24))
            .andExpect(jsonPath("$.h6FontSizePt").value(11));
    }

    @Test
    void rejectsInvalidBooleanWithoutChangingErrorResponseShape() throws Exception {
        mvc.perform(post("/api/convert/docx/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of(
                    "markdown", "text",
                    "includeImages", "true"
                ))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").isString());
    }

    @Test
    void reportsSettingsReadErrorsForSettingsAndConversionRequests() throws Exception {
        String message = "Settings could not be loaded";
        doThrow(new UncheckedIOException(message, new IOException("test failure")))
            .when(settingsService).get();

        mvc.perform(get("/api/settings"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value(message));
        mvc.perform(post("/api/convert/docx/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("markdown", "text"))))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value(message));
    }

    @Test
    void reportsSettingsWriteErrorsAsJson() throws Exception {
        doThrow(new IOException("Settings could not be saved"))
            .when(settingsService).save(any(UserSettings.class));

        mvc.perform(put("/api/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(new UserSettings())))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("Settings could not be saved"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "docx"})
    void preservesMixedCaseAndKoreanUploadNamesInTheActualDownload(String format) throws Exception {
        var file = new MockMultipartFile("file", "C:\\fakepath\\ReadMe_한글.MD", "text/markdown",
            "# Document".getBytes(StandardCharsets.UTF_8));
        var response = response(mvc.perform(multipart("/api/convert/" + format).file(file))
            .andExpect(status().isOk()).andReturn());
        assertThat(response.fileName()).isEqualTo("ReadMe_한글." + format);
        String encoded = java.net.URLEncoder.encode(response.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        byte[] bytes = mvc.perform(get(response.downloadUrl())).andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded))
            .andReturn().getResponse().getContentAsByteArray();
        if ("pdf".equals(format)) {
            try (var document = Loader.loadPDF(bytes)) {
                assertThat(new PDFTextStripper().getText(document)).contains("Document");
            }
        } else {
            try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                assertThat(document.getParagraphs().get(0).getText()).isEqualTo("Document");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"pdf,IGNORE", "pdf,KEEP", "pdf,WARN", "docx,IGNORE", "docx,KEEP", "docx,WARN"})
    void appliesUppercaseLinkStrategiesToActualOutput(String format, String strategy) throws Exception {
        var response = response(mvc.perform(post("/api/convert/" + format + "/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("markdown", "[Link](https://example.com)",
                    "linkStrategy", strategy))))
            .andExpect(status().isOk()).andReturn());
        byte[] bytes = mvc.perform(get(response.downloadUrl())).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        long expected = "IGNORE".equals(strategy) ? 0 : 1;
        if ("pdf".equals(format)) {
            try (var document = Loader.loadPDF(bytes)) {
                assertThat(document.getPage(0).getAnnotations().stream().filter(PDAnnotationLink.class::isInstance).count())
                    .isEqualTo(expected);
            }
        } else {
            try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                assertThat(document.getParagraphs().get(0).getRuns().stream()
                    .filter(org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun.class::isInstance).count()).isEqualTo(expected);
            }
        }
    }

    @Test
    void reportsUnsupportedDocxListDepthAsAnInputError() throws Exception {
        String markdown = java.util.stream.IntStream.range(0, 10)
            .mapToObj(depth -> "    ".repeat(depth) + "- Level " + depth).collect(Collectors.joining("\n"));
        mvc.perform(post("/api/convert/docx/text").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("markdown", markdown))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("9")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"keep", "warn", "ignore"})
    void preservesLinkedZipImagesFormattingAndOriginalLocalTargets(String strategy) throws Exception {
        String target = "files/ReadMe%20One.pdf";
        String markdown = "# Document\n\n[**Before** ![Figure](images/figure.png) *After*](" + target + ")";
        byte[] image = png();
        var archive = new MockMultipartFile("file", "Archive.zip", "application/zip", zip(Map.of(
            "docs/Guide.MD", markdown.getBytes(StandardCharsets.UTF_8), "docs/images/figure.png", image)));
        var response = response(mvc.perform(multipart("/api/convert/docx").file(archive).param("linkStrategy", strategy))
            .andExpect(status().isOk()).andReturn());
        assertThat(response.warnings()).hasSize(strategy.equals("warn") ? 1 : 0);
        mvc.perform(get("/api/convert/" + response.jobId() + "/warnings")).andExpect(status().isOk())
            .andExpect(content().json(mapper.writeValueAsString(response.warnings())));
        try (var doc = new XWPFDocument(new ByteArrayInputStream(download(response, DOCX_CONTENT_TYPE)))) {
            assertThat(doc.getAllPictures()).singleElement().satisfies(picture ->
                assertThat(picture.getData()).isEqualTo(image));
            var paragraph = doc.getParagraphs().get(1);
            assertThat(paragraph.getText()).contains("Before", "After");
            assertThat(paragraph.getRuns()).anySatisfy(run -> {
                assertThat(run.text()).isEqualTo("Before");
                assertThat(run.isBold()).isTrue();
            }).anySatisfy(run -> {
                assertThat(run.text()).isEqualTo("After");
                assertThat(run.isItalic()).isTrue();
            });
            assertThat(paragraph.getCTP().getHyperlinkList()).hasSize(strategy.equals("keep") ? 1 : 0);
            if (strategy.equals("keep")) {
                assertThat(doc.getPackagePart().getRelationship(paragraph.getCTP().getHyperlinkArray(0).getId()).getTargetURI().toString())
                    .isEqualTo(target);
            } else if (strategy.equals("warn")) {
                assertThat(response.warnings().get(0)).containsEntry("type", "ATTACHMENT_NOT_FOUND").containsEntry("target", target);
                assertThat(paragraph.getText()).contains("첨부파일 미포함");
            }
        }
        assertArchiveCleanedUp();
    }

    @Test
    void keepsKoreanPdfAvailableWithMatchingEnvironmentAndConversionWarningsWithoutFonts() throws Exception {
        mvc.perform(get("/api/system/info")).andExpect(status().isOk())
            .andExpect(jsonPath("$.pdfKoreanWarning").value(true))
            .andExpect(jsonPath("$.pdfFonts.fonts").isEmpty());
        ConversionResponse result = convertText("pdf", "한글 본문");
        assertThat(result.warnings()).singleElement().satisfies(warning ->
            assertThat(warning).containsEntry("type", "PDF_KOREAN_FONT_UNAVAILABLE"));
        mvc.perform(get("/api/convert/" + result.jobId() + "/warnings")).andExpect(status().isOk())
            .andExpect(content().json(mapper.writeValueAsString(result.warnings())));
        try (var document = Loader.loadPDF(download(result, MediaType.APPLICATION_PDF_VALUE))) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }

    private ConversionResponse convertText(String format, String markdown) throws Exception {
        return response(mvc.perform(post("/api/convert/" + format + "/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("markdown", markdown))))
            .andExpect(status().isOk())
            .andReturn());
    }

    private ConversionResponse response(MvcResult result) {
        ConversionResponse response = mapper.readValue(result.getResponse().getContentAsByteArray(), ConversionResponse.class);
        assertThat(Instant.parse(response.expiresAt())).isAfter(Instant.now());
        return response;
    }

    private byte[] download(ConversionResponse response, String contentType) throws Exception {
        return mvc.perform(get(response.downloadUrl()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(contentType))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(response.fileName())))
            .andReturn().getResponse().getContentAsByteArray();
    }

    private static byte[] documentBytes(XWPFDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        return out.toByteArray();
    }

    private static byte[] png() throws IOException {
        return png(0);
    }

    private static byte[] png(int rgb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, rgb);
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private record ConversionResponse(String jobId, String downloadUrl, String fileName,
                                      List<Map<String, String>> warnings, String expiresAt) {}
}
