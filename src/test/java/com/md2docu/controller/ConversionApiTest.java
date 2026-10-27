package com.md2docu.controller;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertResult;
import com.md2docu.service.ConvertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConversionApiTest {
    private final ConvertService service = mock(ConvertService.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        var result = new ConvertResult();
        result.setJobId("job");
        result.setDownloadUrl("/api/download/job");
        result.setFileName("Document.docx");
        result.setWarnings(List.of());
        result.setExpiresAt(Instant.parse("2026-10-27T08:00:00Z"));
        when(service.convertText(anyString(), anyString(), any())).thenReturn(result);
        when(service.convertUrl(anyString(), anyString(), any())).thenReturn(result);
        when(service.convertFile(any(), anyString(), any())).thenReturn(result);
        when(service.preview(anyString(), anyBoolean())).thenReturn("");
        mvc = MockMvcBuilders.standaloneSetup(new ConvertController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "file", "url"})
    void normalizesOptionsForEveryInputMode(String mode) throws Exception {
        perform(mode, "DoCx", Map.of(
            "pageSize", "letter", "linkStrategy", "IGNORE", "remoteImageTimeout", 17000,
            "includeImages", false, "generateToc", true, "numberHeadings", true));
        ConvertOptions options = capturedOptions(mode, "docx");
        assertThat(options.getPageSize()).isEqualTo("LETTER");
        assertThat(options.getLinkStrategy()).isEqualTo("ignore");
        assertThat(options.getRemoteImageTimeout()).isEqualTo(17000);
        assertThat(options.isIncludeImages()).isFalse();
        assertThat(options.isGenerateToc()).isTrue();
        assertThat(options.isNumberHeadings()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"text,-2147483648,1000", "file,-2147483648,1000", "url,-2147483648,1000",
        "text,0,1000", "file,0,1000", "url,0,1000",
        "text,1000,1000", "file,1000,1000", "url,1000,1000",
        "text,30000,30000", "file,30000,30000", "url,30000,30000",
        "text,2147483647,30000", "file,2147483647,30000", "url,2147483647,30000"})
    void appliesTheSameTimeoutBoundaries(String mode, int input, int expected) throws Exception {
        perform(mode, "docx", Map.of("remoteImageTimeout", input));
        assertThat(capturedOptions(mode, "docx").getRemoteImageTimeout()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "file", "url"})
    void retainsModelDefaultsForOmittedOptions(String mode) throws Exception {
        perform(mode, "docx", Map.of());
        assertThat(capturedOptions(mode, "docx")).isEqualTo(new ConvertOptions());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"includeImages\":\"true\"}", "{\"includeImages\":null}", "{\"numberHeadings\":1}",
        "{\"generateToc\":[]}", "{\"pageSize\":true}", "{\"pageSize\":null}",
        "{\"linkStrategy\":{}}", "{\"remoteImageTimeout\":\"5000\"}",
        "{\"remoteImageTimeout\":1.5}", "{\"remoteImageTimeout\":2147483648}",
        "{\"remoteImageTimeout\":null}"
    })
    void rejectsWrongJsonTypesWithoutCallingConversion(String fields) throws Exception {
        for (String mode : List.of("text", "url")) {
            var body = mapper.readTree(fields);
            var object = mapper.createObjectNode().put("markdown", "Text").put("url", "https://example.com/doc.md");
            object.setAll((tools.jackson.databind.node.ObjectNode) body);
            mvc.perform(post("/api/convert/docx/" + mode).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(object)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"true\"", "1", "null", "[]", "{}"})
    void validatesPreviewBooleansLikeConversionBooleans(String value) throws Exception {
        mvc.perform(post("/api/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"markdown\":\"Text\",\"numberHeadings\":" + value + "}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "true", "null", "{\"markdown\":42}"})
    void returnsJsonForMalformedRequestBodies(String body) throws Exception {
        mvc.perform(post("/api/convert/docx/text").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
    }

    @ParameterizedTest
    @CsvSource({"includeImages,yes", "numberHeadings,1", "generateToc,no", "remoteImageTimeout,1.5"})
    void rejectsInvalidFormTypes(String field, String value) throws Exception {
        mvc.perform(multipart("/api/convert/docx").file(markdownFile()).param(field, value))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
        verifyNoInteractions(service);
    }

    @Test
    void distinguishesMissingResultsFromResultsWithNoWarnings() throws Exception {
        when(service.getWarnings("missing")).thenReturn(null);
        mvc.perform(get("/api/download/missing")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").isString());
        mvc.perform(get("/api/convert/missing/warnings")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").isString());
        when(service.getWarnings("job")).thenReturn(List.of());
        mvc.perform(get("/api/convert/job/warnings")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void treatsInternalIoFailuresAsServerFailures() throws Exception {
        when(service.convertText(anyString(), anyString(), any())).thenThrow(new IOException("Failed to write output"));
        mvc.perform(post("/api/convert/docx/text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"markdown\":\"Text\"}"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error").isString());
    }

    @ParameterizedTest
    @CsvSource({"text,pageSize,Legal", "file,pageSize,Legal", "url,pageSize,Legal",
        "text,linkStrategy,unknown", "file,linkStrategy,unknown", "url,linkStrategy,unknown"})
    void rejectsUnknownOptionsBeforeCallingTheService(String mode, String field, String value) throws Exception {
        if ("file".equals(mode)) {
            mvc.perform(multipart("/api/convert/docx").file(markdownFile()).param(field, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
        } else {
            mvc.perform(post("/api/convert/docx/" + mode).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(Map.of("markdown", "Text", "url", "https://example.com/doc.md", field, value))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
        }
        verifyNoInteractions(service);
    }

    @Test
    void normalizesWithoutDependingOnTheServerLocale() throws Exception {
        var previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            perform("text", "PDF", Map.of("linkStrategy", " IGNORE ", "pageSize", " letter "));
            assertThat(capturedOptions("text", "pdf").getLinkStrategy()).isEqualTo("ignore");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @ParameterizedTest
    @CsvSource({"text,markdown", "url,url", "preview,markdown"})
    void rejectsWrongSourceFieldTypes(String mode, String field) throws Exception {
        String path = "preview".equals(mode) ? "/api/preview" : "/api/convert/docx/" + mode;
        for (String value : List.of("null", "42", "true", "[]")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"" + field + "\":" + value + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
        }
    }

    @Test
    void returnsJsonForMissingFilesAndUnsupportedMediaTypes() throws Exception {
        mvc.perform(multipart("/api/convert/docx")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").isString());
        mvc.perform(post("/api/convert/docx/text").contentType(MediaType.TEXT_PLAIN).content("Text"))
            .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error").isString());
        mvc.perform(post("/api/convert/png/text").contentType(MediaType.APPLICATION_JSON).content("{\"markdown\":\"Text\"}"))
            .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error").isString());
        mvc.perform(multipart("/api/convert/md").file(markdownFile()).param("splitByChapter", "yes"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 413, 415, 502, 504})
    void preservesClassifiedServiceFailuresAsJson(int code) throws Exception {
        when(service.convertUrl(anyString(), anyString(), any()))
            .thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.valueOf(code), "Classified failure"));
        mvc.perform(post("/api/convert/docx/url").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/doc.md\"}"))
            .andExpect(status().is(code)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").value("Classified failure"));
    }

    @Test
    void reportsUnexpectedRuntimeFailuresWithoutExposingDiagnostics() throws Exception {
        when(service.convertText(anyString(), anyString(), any())).thenThrow(new IllegalStateException("private diagnostic"));
        var response = mvc.perform(post("/api/convert/docx/text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"markdown\":\"Text\"}"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error").isString())
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("private diagnostic");
    }

    private void perform(String mode, String format, Map<String, Object> options) throws Exception {
        RequestBuilder request;
        if ("file".equals(mode)) {
            var upload = multipart("/api/convert/" + format).file(markdownFile());
            options.forEach((key, value) -> upload.param(key, String.valueOf(value)));
            request = upload;
        } else {
            Map<String, Object> body = new LinkedHashMap<>(options);
            body.put("markdown", "Text");
            body.put("url", "https://example.com/doc.md");
            request = post("/api/convert/" + format + "/" + mode).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body));
        }
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value("job"))
            .andExpect(jsonPath("$.downloadUrl").value("/api/download/job"))
            .andExpect(jsonPath("$.expiresAt").value("2026-10-27T08:00:00Z"))
            .andExpect(jsonPath("$.fileName").value("Document.docx")).andExpect(jsonPath("$.warnings").isArray());
    }

    private ConvertOptions capturedOptions(String mode, String format) throws IOException {
        var captor = ArgumentCaptor.forClass(ConvertOptions.class);
        switch (mode) {
            case "text" -> verify(service).convertText(anyString(), eq(format), captor.capture());
            case "url" -> verify(service).convertUrl(anyString(), eq(format), captor.capture());
            case "file" -> verify(service).convertFile(any(), eq(format), captor.capture());
            default -> throw new AssertionError(mode);
        }
        return captor.getValue();
    }

    private MockMultipartFile markdownFile() {
        return new MockMultipartFile("file", "Document.MD", "text/markdown", "Text".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
