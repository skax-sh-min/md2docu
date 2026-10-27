package com.md2docu;

import com.md2docu.model.ConvertResult;
import com.md2docu.service.ConvertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.pdf.font.paths=", "spring.servlet.multipart.max-file-size=16B",
    "spring.servlet.multipart.max-request-size=1KB"
})
class MultipartLimitTest {
    @LocalServerPort private int port;
    @MockitoBean private ConvertService service;
    @Autowired private ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicLong observedSize = new AtomicLong(-1);

    @BeforeEach
    void setUp() throws Exception {
        var result = new ConvertResult();
        result.setJobId("job");
        result.setFileName("Document.docx");
        result.setDownloadUrl("/api/download/job");
        result.setWarnings(List.of());
        when(service.convertFile(any(), anyString(), any())).thenAnswer(invocation -> {
            MultipartFile file = invocation.getArgument(0);
            observedSize.set(file.getSize());
            return result;
        });
    }

    @ParameterizedTest
    @CsvSource({"16,0,200", "17,0,413", "16,1024,200", "16,1025,413"})
    void enforcesActualServletFileAndRequestLimitsWithJsonErrors(int fileSize, int totalSize, int status) throws Exception {
        String body = multipartBody(fileSize, totalSize);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/convert/docx"))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "multipart/form-data; boundary=boundary")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/json");
        var json = mapper.readTree(response.body());
        if (status == 413) {
            assertThat(json.path("error").asText()).isNotBlank();
            verifyNoInteractions(service);
        } else {
            assertThat(json.path("jobId").asText()).isEqualTo("job");
            assertThat(observedSize).hasValue(fileSize);
            verify(service).convertFile(any(), eq("docx"), any());
        }
    }

    private String multipartBody(int fileSize, int totalSize) {
        String prefix = "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"Document.md\"\r\n"
            + "Content-Type: text/markdown\r\n\r\n" + "a".repeat(fileSize);
        String field = "\r\n--boundary\r\nContent-Disposition: form-data; name=\"padding\"\r\n\r\n";
        String suffix = "\r\n--boundary--\r\n";
        if (totalSize == 0) return prefix + suffix;
        for (int count = 1; count < 32; count++) {
            int remaining = totalSize - prefix.length() - suffix.length() - field.length() * count;
            if (remaining < 0 || remaining > count * 16) continue;
            StringBuilder body = new StringBuilder(prefix);
            for (int i = 0; i < count; i++) {
                int size = Math.min(16, remaining);
                body.append(field).append("x".repeat(size));
                remaining -= size;
            }
            return body.append(suffix).toString();
        }
        throw new AssertionError("Cannot create multipart body of " + totalSize + " bytes");
    }
}
