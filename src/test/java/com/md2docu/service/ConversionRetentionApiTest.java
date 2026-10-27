package com.md2docu.service;

import com.md2docu.controller.ApiExceptionHandler;
import com.md2docu.controller.ConvertController;
import com.md2docu.util.ZipExtractor;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConversionRetentionApiTest {
    private static final Instant START = Instant.parse("2026-10-27T00:00:00Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = mock(Clock.class);
    private final DocxConverter docx = mock(DocxConverter.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private ConversionResultStore store;
    private ConvertController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        when(clock.instant()).thenAnswer(invocation -> now.get());
        store = new ConversionResultStore(10, 2, 10, clock);
        when(docx.convert(anyString(), any(), any(), anyList())).thenReturn(new byte[]{1, 2});
        var service = new ConvertService(new MarkdownService(), mock(PdfConverter.class), docx,
            new DocxToMarkdownConverter(), new ZipExtractor(), store);
        controller = new ConvertController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void expiresDownloadsAndWarningsTogetherAtTheBoundaryWithoutCleanup(long offsetNanos) throws Exception {
        JsonNode response = convertText();
        assertThat(response.path("expiresAt").asText()).isEqualTo("2026-10-27T00:00:10Z");
        now.set(START.plusSeconds(10).plusNanos(offsetNanos));
        String id = response.path("jobId").asText();
        if (offsetNanos < 0) {
            mvc.perform(get(response.path("downloadUrl").asText())).andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{1, 2})).andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(get("/api/convert/" + id + "/warnings")).andExpect(status().isOk())
                .andExpect(content().json("[]")).andExpect(header().string("Cache-Control", "no-store"));
        } else {
            assertMissing(response);
        }
    }

    @Test
    void countEvictionMakesBothEndpointsReturnJson404() throws Exception {
        JsonNode first = convertText();
        JsonNode second = convertText();
        JsonNode third = convertText();
        assertMissing(first);
        for (JsonNode response : new JsonNode[]{second, third}) {
            mvc.perform(get(response.path("downloadUrl").asText())).andExpect(status().isOk());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "file", "reverse", "split"})
    void returnsJson413ForOversizedResultsWithoutEvictingAnExistingResult(String mode) throws Exception {
        JsonNode existing = convertText();
        when(docx.convert(anyString(), any(), any(), anyList())).thenReturn(new byte[11]);
        mvc.perform(request(mode)).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error").isString());
        mvc.perform(get(existing.path("downloadUrl").asText())).andExpect(status().isOk())
            .andExpect(content().bytes(new byte[]{1, 2}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "reverse", "split"})
    void includesExpiryInForwardAndReverseConversionResponses(String mode) throws Exception {
        // Reverse chapter ZIPs are larger than the deliberately tiny default test budget.
        store = new ConversionResultStore(10, 2, 100000, clock);
        var service = new ConvertService(new MarkdownService(), mock(PdfConverter.class), docx,
            new DocxToMarkdownConverter(), new ZipExtractor(), store);
        var local = MockMvcBuilders.standaloneSetup(new ConvertController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        local.perform(request(mode)).andExpect(status().isOk())
            .andExpect(jsonPath("$.expiresAt").value("2026-10-27T00:00:10Z"))
            .andExpect(jsonPath("$.downloadUrl").isString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anInFlightHttpDownloadCompletesWhileExpiryOrCapacityEvictionRemovesItsStoredResult(boolean expire) throws Exception {
        JsonNode response = convertText();
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var gate = new AtomicBoolean(true);
        var gatedMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
            .addFilter((request, servletResponse, chain) -> {
                if (!gate.getAndSet(false)) {
                    chain.doFilter(request, servletResponse);
                    return;
                }
                chain.doFilter(request, new HttpServletResponseWrapper((HttpServletResponse) servletResponse) {
                    @Override
                    public ServletOutputStream getOutputStream() throws IOException {
                        ServletOutputStream delegate = super.getOutputStream();
                        return new ServletOutputStream() {
                            @Override public boolean isReady() { return delegate.isReady(); }
                            @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                            @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
                            @Override
                            public void write(byte[] bytes, int offset, int length) throws IOException {
                                writing.countDown();
                                try {
                                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Download release timed out");
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                    throw new IOException("Download interrupted", exception);
                                }
                                delegate.write(bytes, offset, length);
                            }
                        };
                    }
                });
            }).build();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var download = executor.submit(() -> gatedMvc.perform(get(response.path("downloadUrl").asText()))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1, 2})));
            try {
                assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();
                if (expire) {
                    now.set(START.plusSeconds(10));
                    store.evictExpired();
                } else {
                    convertText();
                    convertText();
                }
                assertMissing(response);
            } finally {
                release.countDown();
            }
            download.get(10, TimeUnit.SECONDS);
        }
    }

    private JsonNode convertText() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/convert/docx/text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"markdown\":\"Text\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private void assertMissing(JsonNode response) throws Exception {
        mvc.perform(get(response.path("downloadUrl").asText())).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").isString());
        mvc.perform(get("/api/convert/" + response.path("jobId").asText() + "/warnings"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").isString());
    }

    private RequestBuilder request(String mode) throws IOException {
        if (mode.equals("text")) {
            return post("/api/convert/docx/text").contentType(MediaType.APPLICATION_JSON).content("{\"markdown\":\"Text\"}");
        }
        if (mode.equals("file")) {
            return multipart("/api/convert/docx")
                .file(new MockMultipartFile("file", "Guide.md", "text/markdown", new byte[]{'T'}));
        }
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("A paragraph larger than ten bytes");
            document.write(bytes);
            return multipart("/api/convert/md")
                .file(new MockMultipartFile("file", "Guide.docx", "application/octet-stream", bytes.toByteArray()))
                .param("splitByChapter", String.valueOf(mode.equals("split")));
        }
    }
}
