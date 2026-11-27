package com.md2docu.service;

import com.md2docu.util.ZipExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ConvertServiceDownloadTest {
    private final ConvertService service = new ConvertService(new MarkdownService(), mock(PdfConverter.class),
        mock(DocxConverter.class), new DocxToMarkdownConverter(), new ZipExtractor(),
        new ConversionResultStore(3600, 500, 268435456));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(35)
    void enforcesOneFifteenSecondDeadlineAndClosesStalledOrTricklingBodies(boolean trickle) throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var disconnected = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(25_000);
                    readHeaders(socket.getInputStream());
                    if (trickle) Thread.sleep(3_000);
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n#"
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    if (trickle) {
                        try {
                            for (int i = 0; i < 22; i++) {
                                Thread.sleep(1_000);
                                socket.getOutputStream().write('#');
                                socket.getOutputStream().flush();
                            }
                            return false;
                        } catch (SocketException exception) {
                            return true;
                        }
                    }
                    return socket.getInputStream().read() == -1;
                }
            });
            long start = System.nanoTime();
            assertThatThrownBy(() -> download(server.getLocalPort()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(504));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            assertThat(elapsed).isBetween(Duration.ofSeconds(14), Duration.ofSeconds(17));
            assertThat(disconnected.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 404, 503})
    @Timeout(10)
    void rejectsHttpFailuresWithoutWaitingForTheirBodyAndClosesTheConnection(int status) throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var disconnected = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    readHeaders(socket.getInputStream());
                    socket.getOutputStream().write(
                        ("HTTP/1.1 " + status + " Failure\r\nContent-Length: 2\r\nConnection: close\r\n\r\n#")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return socket.getInputStream().read() == -1;
                }
            });
            assertThatThrownBy(() -> download(server.getLocalPort()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(502))
                .hasMessageContaining("HTTP " + status);
            assertThat(disconnected.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Timeout(10)
    void readsACompleteSuccessfulBody() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    readHeaders(socket.getInputStream());
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nText"
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                return true;
            });
            assertThat(download(server.getLocalPort())).isEqualTo("Text".getBytes(StandardCharsets.UTF_8));
            assertThat(response.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private byte[] download(int port) {
        return ReflectionTestUtils.invokeMethod(service, "downloadFromUrl", "http://127.0.0.1:" + port + "/test.md");
    }

    private void readHeaders(java.io.InputStream input) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.US_ASCII));
        String line;
        do {
            line = reader.readLine();
            if (line == null) throw new java.io.EOFException("Missing request headers");
        } while (!line.isEmpty());
    }
}
