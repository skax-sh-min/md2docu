package com.md2docu.service;

import com.md2docu.model.ConvertOptions;
import com.md2docu.model.ConvertResult;
import com.md2docu.util.ZipExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConvertServiceInputTest {
    private final PdfConverter pdf = mock(PdfConverter.class);
    private final DocxConverter docx = mock(DocxConverter.class);
    private final HttpClient client = mock(HttpClient.class);
    private final ZipExtractor extractor = spy(new ZipExtractor());
    private final ConvertOptions options = new ConvertOptions();
    private ConvertService service;

    @BeforeEach
    void setUp() throws IOException {
        ReflectionTestUtils.setField(extractor, "maxExtractBytes", 1_000_000L);
        service = new ConvertService(new MarkdownService(), pdf, docx, new DocxToMarkdownConverter(),
            extractor, new ConversionResultStore(3600, 500, 268435456), client);
        when(docx.convert(anyString(), any(), any(), anyList())).thenReturn(new byte[]{1});
        when(pdf.convert(anyString(), any(), any(), anyList())).thenReturn(new byte[]{2});
    }

    @ParameterizedTest
    @CsvSource({"ReadMe_한글.MD,ReadMe_한글.docx", "C:\\fakepath\\Report.Md,Report.docx",
        "/upload/Report Name.MD,Report Name.docx"})
    void preservesOriginalCaseAndUnicodeWithoutClientDirectories(String name, String expected) throws Exception {
        ConvertResult result = service.convertFile(file(name, "Text".getBytes(StandardCharsets.UTF_8)), "docx", options);
        assertThat(result.getFileName()).isEqualTo(expected);
        assertThat(service.getResult(result.getJobId())).isEqualTo(result).isNotSameAs(result);
        assertThat(service.getWarnings(result.getJobId())).isEmpty();
        assertThat(service.getResult("missing")).isNull();
        assertThat(service.getWarnings("missing")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Document.txt", "Document.pdf", "Document.docx", "Document", "Document.md.exe"})
    void rejectsUnsupportedUploadsBeforeConversion(String name) {
        assertStatus(() -> service.convertFile(file(name, new byte[]{1}), "docx", options), 415);
        verifyNoInteractions(pdf, docx);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "file", "zip", "url"})
    void rejectsBlankMarkdownAcrossAllInputs(String mode) throws Exception {
        byte[] content = "\uFEFF \t\r\n".getBytes(StandardCharsets.UTF_8);
        if ("url".equals(mode)) remote(200, content);
        assertStatus(() -> convert(mode, content), 400);
        verifyNoInteractions(pdf, docx);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "zip", "url"})
    void rejectsInvalidUtf8InsteadOfReplacingLostCharacters(String mode) throws Exception {
        byte[] content = {(byte) 0xc3, 0x28};
        if ("url".equals(mode)) remote(200, content);
        assertStatus(() -> convert(mode, content), 400);
        verifyNoInteractions(pdf, docx);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "file", "zip", "url"})
    void acceptsUtf8BomAndPreservesMarkdownContent(String mode) throws Exception {
        byte[] content = "\uFEFF# Title\n\nBody".getBytes(StandardCharsets.UTF_8);
        if ("url".equals(mode)) remote(200, content);
        convert(mode, content);
        var html = ArgumentCaptor.forClass(String.class);
        verify(docx).convert(html.capture(), any(), any(), anyList());
        assertThat(html.getValue()).contains("Title", "Body").doesNotContain("\uFEFF");
        if ("zip".equals(mode)) assertCleaned();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Document.md", "Document.zip", "Document.docx"})
    void rejectsZeroByteUploads(String name) {
        assertStatus(() -> {
            if (name.endsWith(".docx")) service.convertDocxToMd(file(name, new byte[0]), false);
            else service.convertFile(file(name, new byte[0]), "docx", options);
        }, 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "zip", "xml"})
    void reportsCorruptDocxAsInputErrors(String kind) throws Exception {
        byte[] bytes = "Not a Word document".getBytes(StandardCharsets.UTF_8);
        if ("zip".equals(kind)) bytes = zip("hello.txt", bytes);
        if ("xml".equals(kind)) bytes = zip("word/document.xml", "<broken".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = file("Report.DOCX", bytes);
        assertStatus(() -> service.convertDocxToMd(file, false), 400);
    }

    @Test
    void acceptsValidBlankDocxAndPreservesItsName() throws Exception {
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            document.write(bytes);
            ConvertResult result = service.convertDocxToMd(file("Report_한글.DOCX", bytes.toByteArray()), false);
            assertThat(result.getFileName()).isEqualTo("Report_한글.md");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not XML", "<other/>",
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p></w:body></w:document>"})
    void reportsInvalidWordXmlInsideAnOtherwiseValidPackageAs400(String xml) throws Exception {
        var original = new ByteArrayOutputStream();
        try (var document = new XWPFDocument()) {
            document.write(original);
        }
        var changed = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(original.toByteArray()));
             var output = new ZipOutputStream(changed)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                output.write("word/document.xml".equals(entry.getName())
                    ? xml.getBytes(StandardCharsets.UTF_8) : input.readAllBytes());
                output.closeEntry();
            }
        }
        var file = file("Document.docx", changed.toByteArray());
        assertStatus(() -> service.convertDocxToMd(file, false), 400);
    }

    @Test
    void doesNotSelectADirectoryWhoseNameEndsInMd() throws Exception {
        var result = service.convertFile(file("Document.zip",
            zip("docs.md/Guide.MD", "Text".getBytes(StandardCharsets.UTF_8))), "docx", options);
        assertThat(result.getFileName()).isEqualTo("Guide.docx");
        assertCleaned();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 403, 404, 500, 503})
    void classifiesUpstreamHttpFailuresSeparately(int status) throws Exception {
        remote(status, new byte[0]);
        assertStatus(() -> service.convertUrl("http://8.8.8.8/Document.md", "docx", options), 502);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void distinguishesUpstreamIoFromTimeout(boolean timeout) throws Exception {
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
            .thenReturn(CompletableFuture.failedFuture(
                timeout ? new HttpTimeoutException("timeout") : new IOException("connection failed")));
        assertStatus(() -> service.convertUrl("http://8.8.8.8/Document.md", "docx", options), timeout ? 504 : 502);
    }

    @Test
    void treatsDnsFailureAsUpstreamFailureWithoutExternalDns() {
        try (var dns = mockStatic(InetAddress.class)) {
            dns.when(() -> InetAddress.getAllByName("host.invalid")).thenThrow(new UnknownHostException("test"));
            assertStatus(() -> service.convertUrl("http://host.invalid/Document.md", "docx", options), 502);
        }
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "file:///Document.md", "http://localhost/Document.md", "http://127.0.0.1/Document.md",
        "http://[::1]/Document.md", "http://user:password@8.8.8.8/Document.md", "http://8.8.8.8:99999/Document.md",
        "http://8.8.8.8/%GG.md"})
    void rejectsInvalidOrBlockedUrlsBeforeHttp(String url) {
        assertStatus(() -> service.convertUrl(url, "docx", options), 400);
        verifyNoInteractions(client);
    }

    @Test
    void checksUrlExtensionBeforeDownloading() {
        assertStatus(() -> service.convertUrl("http://8.8.8.8/Document.exe", "docx", options), 415);
        verifyNoInteractions(client);
    }

    @Test
    void preservesDecodedUrlFilename() throws Exception {
        remote(200, "Text".getBytes(StandardCharsets.UTF_8));
        ConvertResult result = service.convertUrl("http://8.8.8.8/ReadMe_%ED%95%9C%EA%B8%80.MD", "docx", options);
        assertThat(result.getFileName()).isEqualTo("ReadMe_한글.docx");
    }

    @Test
    void reportsDownloadedHtmlAsInvalidInput() throws Exception {
        remote(200, "\uFEFF<!DOCTYPE html><html></html>".getBytes(StandardCharsets.UTF_8));
        assertStatus(() -> service.convertUrl("http://8.8.8.8/Document.md", "docx", options), 400);
    }

    @ParameterizedTest
    @ValueSource(ints = {10485760, 10485761})
    void checksTheExactDownloadByteLimit(int bytes) throws Exception {
        byte[] content = new byte[bytes];
        Arrays.fill(content, (byte) 'a');
        remote(200, content);
        var markdown = mock(MarkdownService.class);
        when(markdown.toHtml(anyString(), anyBoolean(), anyBoolean())).thenReturn("<p>Text</p>");
        var controlled = new ConvertService(markdown, pdf, docx, new DocxToMarkdownConverter(),
            extractor, new ConversionResultStore(3600, 500, 268435456), client);
        if (bytes > 10 * 1024 * 1024) {
            assertStatus(() -> controlled.convertUrl("http://8.8.8.8/Document.md", "docx", options), 413);
            verifyNoInteractions(markdown);
        } else {
            assertThat(controlled.convertUrl("http://8.8.8.8/Document.md", "docx", options).getFileBytes()).containsExactly(1);
            verify(markdown).toHtml(argThat(text -> text.length() == bytes), eq(false), eq(false));
        }
    }

    @Test
    void reportsExtractionSizeLimitAs413AndRemovesPartialFiles() throws Exception {
        ReflectionTestUtils.setField(extractor, "maxExtractBytes", 10L);
        byte[] archive = zip("Document.md", "More than ten bytes".getBytes(StandardCharsets.UTF_8));
        assertStatus(() -> service.convertFile(file("Document.zip", archive), "docx", options), 413);
        assertCleaned();
    }

    @Test
    void reportsTruncatedZipAs400AndRemovesPartialFiles() throws Exception {
        byte[] archive = zip("Document.md", "Text body for compression".getBytes(StandardCharsets.UTF_8));
        byte[] truncated = Arrays.copyOf(archive, 45);
        assertStatus(() -> service.convertFile(file("Document.zip", truncated), "docx", options), 400);
        assertCleaned();
    }

    @Test
    void reportsNonZipDataAs400() {
        assertStatus(() -> service.convertFile(file("Document.zip", new byte[]{1, 2, 3, 4}), "docx", options), 400);
    }

    @Test
    void allowsBlankPreview() {
        assertThat(service.preview("", false)).isEmpty();
    }

    @Test
    void restoresInterruptFlagAndDoesNotMisclassifyCancellationAsBadInput() throws Exception {
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
            .thenReturn(pending);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> service.convertUrl("http://8.8.8.8/Document.md", "docx", options))
                .isInstanceOf(IOException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(pending.isCancelled()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private ConvertResult convert(String mode, byte[] content) throws Exception {
        return switch (mode) {
            case "text" -> service.convertText(new String(content, StandardCharsets.UTF_8), "docx", options);
            case "file" -> service.convertFile(file("Document.MD", content), "docx", options);
            case "zip" -> service.convertFile(file("Document.ZIP", zip("docs/Document.MD", content)), "docx", options);
            case "url" -> service.convertUrl("http://8.8.8.8/Document.md", "docx", options);
            default -> throw new AssertionError(mode);
        };
    }

    private void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, int status) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(status));
    }

    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    @SuppressWarnings("unchecked")
    private void remote(int status, byte[] content) throws Exception {
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
            .thenAnswer(invocation -> {
                HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                HttpResponse.ResponseInfo info = mock(HttpResponse.ResponseInfo.class);
                when(info.statusCode()).thenReturn(status);
                try {
                    HttpResponse.BodySubscriber<byte[]> subscriber = handler.apply(info);
                    Flow.Subscription subscription = mock(Flow.Subscription.class);
                    subscriber.onSubscribe(subscription);
                    for (int offset = 0; offset < content.length; offset += 65536) {
                        subscriber.onNext(List.of(ByteBuffer.wrap(content, offset,
                            Math.min(65536, content.length - offset)).asReadOnlyBuffer()));
                    }
                    subscriber.onComplete();
                    if (content.length > 10 * 1024 * 1024) verify(subscription).cancel();
                    return subscriber.getBody().thenApply(bytes -> {
                        HttpResponse<byte[]> response = mock(HttpResponse.class);
                        when(response.body()).thenReturn(bytes);
                        return response;
                    }).toCompletableFuture();
                } catch (ResponseStatusException exception) {
                    return CompletableFuture.failedFuture(exception);
                }
            });
    }

    private byte[] zip(String name, byte[] content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private void assertCleaned() {
        var path = ArgumentCaptor.forClass(Path.class);
        verify(extractor).cleanup(path.capture());
        assertThat(path.getValue()).doesNotExist();
    }
}
