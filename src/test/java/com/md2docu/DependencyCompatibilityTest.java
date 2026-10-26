package com.md2docu;

import com.md2docu.model.UserSettings;
import com.md2docu.service.UserSettingsService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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

    @BeforeEach
    void useInMemorySettings() {
        doReturn(new UserSettings()).when(settingsService).get();
    }

    @Test
    void rendersThymeleafPageWithMatchingBootstrapAssets() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("bootstrap@5.3.8/dist/css/bootstrap.min.css")))
            .andExpect(content().string(containsString("bootstrap@5.3.8/dist/js/bootstrap.bundle.min.js")));
    }

    @Test
    void previewsMarkdownThroughJacksonAndSpringMvc() throws Exception {
        mvc.perform(post("/api/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("markdown", "# Hello\n\n**world**"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.html", containsString("<strong>world</strong>")));
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

    private ConversionResponse convertText(String format, String markdown) throws Exception {
        return response(mvc.perform(post("/api/convert/" + format + "/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("markdown", markdown))))
            .andExpect(status().isOk())
            .andReturn());
    }

    private ConversionResponse response(MvcResult result) {
        return mapper.readValue(result.getResponse().getContentAsByteArray(), ConversionResponse.class);
    }

    private byte[] download(ConversionResponse response, String contentType) throws Exception {
        return mvc.perform(get(response.downloadUrl()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(contentType))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(response.fileName())))
            .andReturn().getResponse().getContentAsByteArray();
    }

    private static byte[] documentBytes(XWPFDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        return out.toByteArray();
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
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
                                      List<Map<String, String>> warnings) {}
}
