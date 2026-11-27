package com.md2docu.util;

import com.md2docu.TestImages;
import com.md2docu.TestImages.Format;
import com.md2docu.model.ConvertWarning;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.xwpf.usermodel.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ImageResolverFormatsTest {
    @TempDir Path directory;
    private final ImageResolver resolver = new ImageResolver();
    private final List<ConvertWarning> warnings = new ArrayList<>();
    private HttpURLConnection connection;

    enum Source { LOCAL, REMOTE, DATA }

    static Stream<Arguments> formatsAndSources() {
        return Arrays.stream(Format.values()).flatMap(format ->
            Arrays.stream(Source.values()).map(source -> Arguments.of(format, source)));
    }

    @ParameterizedTest
    @MethodSource("formatsAndSources")
    void identifiesActualBytesAcrossAllSourcesDespiteIncorrectHints(Format format, Source source) throws Exception {
        byte[] bytes = TestImages.bytes(format);
        String src = switch (source) {
            case LOCAL -> "image.wrong";
            case REMOTE -> "https://images.example.test/content?filename=wrong.jpg";
            case DATA -> "data:image/jpeg;charset=utf-8;base64," + Base64.getEncoder().encodeToString(bytes);
        };
        if (source == Source.LOCAL) Files.write(directory.resolve(src), bytes);
        try (MockedStatic<URI> remote = source == Source.REMOTE ? remote(src, bytes, "image/jpeg") : null) {
            var image = resolver.resolve(src, new ImageResolver.LocalImagePaths(directory, directory), warnings, 5000);
            assertThat(image).isNotNull();
            assertThat(image.mimeType()).isEqualTo(format.outputMime);
            int expectedType = switch (format) {
                case JPEG -> Document.PICTURE_TYPE_JPEG;
                case GIF -> Document.PICTURE_TYPE_GIF;
                case BMP -> Document.PICTURE_TYPE_BMP;
                default -> Document.PICTURE_TYPE_PNG;
            };
            assertThat(image.pictureType()).isEqualTo(expectedType);
            assertThat(image.width()).isEqualTo(16);
            assertThat(image.height()).isEqualTo(10);
            assertThat(ImageIO.read(new ByteArrayInputStream(image.bytes())).getWidth()).isEqualTo(16);
            if (format != Format.SVG && format != Format.WEBP) assertThat(image.bytes()).isEqualTo(bytes);
            assertThat(warnings).isEmpty();
            if (source == Source.REMOTE) {
                verify(connection).setInstanceFollowRedirects(false);
                verify(connection).disconnect();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void producesMatchingDataUriMimeFromBytesRatherThanTheExtension(Format format) throws Exception {
        Files.write(directory.resolve("wrong.jpg"), TestImages.bytes(format));
        String result = resolver.resolveToBase64DataUri("wrong.jpg",
            new ImageResolver.LocalImagePaths(directory, directory), warnings, 5000);
        String mime = format == Format.GIF ? "image/png" : format.outputMime;
        assertThat(result).startsWith("data:" + mime + ";base64,");
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"data:image/png;base64,%%==", "data:image/png;base64,", "data:image/png;base64,A"})
    void reportsInvalidDataOnce(String src) {
        assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
        assertWarning("IMAGE_INVALID_DATA", src);
    }

    @Test
    void rejectsTruncatedPngInsteadOfTreatingItAsAnEmbeddedImage() throws Exception {
        String src = TestImages.dataUri(Arrays.copyOf(TestImages.bytes(Format.PNG), 20));
        assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
        assertWarning("IMAGE_INVALID_DATA", src);
    }

    @Test
    void reportsUnsupportedTiffWithoutPretendingItIsJpeg() throws Exception {
        String src = TestImages.dataUri(TestImages.raster("tiff", 16, 10));
        assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
        assertWarning("IMAGE_UNSUPPORTED_FORMAT", src);
    }

    @Test
    void reportsLocalImagesWithoutAnUploadDirectory() {
        assertThat(resolver.resolve("image.png", null, warnings, 5000)).isNull();
        assertWarning("IMAGE_NOT_FOUND", "image.png");
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg", "gif", "bmp"})
    void acceptsExactlySixMillionPixelsAndRejectsLargerImages(String format) throws Exception {
        String atLimit = TestImages.dataUri(TestImages.raster(format, 3000, 2000));
        assertThat(resolver.resolve(atLimit, null, warnings, 5000)).isNotNull();
        assertThat(warnings).isEmpty();
        String overLimit = TestImages.dataUri(TestImages.raster(format, 3000, 2001));
        assertThat(resolver.resolve(overLimit, null, warnings, 5000)).isNull();
        assertWarning("IMAGE_TOO_LARGE", overLimit);
    }

    @Test
    void enforcesTheExactPixelProductWithoutCroppingOrAssumingLandscapeOrientation() {
        ImageResolver.checkDimensions(2000, 3000);
        ImageResolver.checkDimensions(6000, 1000);
        ImageResolver.checkDimensions(6_000_000, 1);
        assertThatThrownBy(() -> ImageResolver.checkDimensions(6_000_001, 1))
            .isInstanceOf(ImageResolver.ImageTooLargeException.class);
        assertThatThrownBy(() -> ImageResolver.checkDimensions(Integer.MAX_VALUE, Integer.MAX_VALUE))
            .isInstanceOf(ImageResolver.ImageTooLargeException.class);
    }

    @Test
    void rejectsOversizedSvgBeforeAllocatingTheRaster() {
        String src = TestImages.dataUri(TestImages.svg(3000, 2001, "<rect width='100%' height='100%'/>"));
        assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
        assertWarning("IMAGE_TOO_LARGE", src);
    }

    @Test
    void checksWebpDimensionsBeforeDecodingItsPixels() throws Exception {
        byte[] source = TestImages.bytes(Format.WEBP);
        ByteBuffer.wrap(source, 21, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt((3000 - 1) | ((2001 - 1) << 14));
        String src = TestImages.dataUri(source);
        assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
        assertWarning("IMAGE_TOO_LARGE", src);
    }

    @Test
    void appliesThePixelLimitToTheGifCanvasEvenWhenItsFirstFrameIsSmall() throws Exception {
        byte[] source = TestImages.bytes(Format.GIF);
        ByteBuffer.wrap(source, 6, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putShort((short) 3000).putShort((short) 2001);
        assertThat(ImageIO.read(new ByteArrayInputStream(source)).getWidth()).isEqualTo(16);
        String src = TestImages.dataUri(source);
        assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
        assertWarning("IMAGE_TOO_LARGE", src);
    }

    @Test
    void rasterizesStaticSvgWithoutExecutingEmbeddedScripts() {
        String src = TestImages.dataUri(TestImages.svg(16, 10,
            "<script type='text/ecmascript'>throw new Error('must not execute');</script>"
                + "<rect width='16' height='10' fill='#2455aa'/>"));
        assertThat(resolver.resolve(src, null, warnings, 5000)).isNotNull();
        assertThat(warnings).isEmpty();
    }

    @Test
    void warnsForUnsupportedSvgForeignObjectsRatherThanDroppingTheirContent() {
        String src = TestImages.dataUri(TestImages.svg(16, 10,
            "<foreignObject width='16' height='10'><p xmlns='http://www.w3.org/1999/xhtml'>Text</p></foreignObject>"));
        assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
        assertWarning("IMAGE_CONVERSION_FAILED", src);
    }

    @Test
    void supportsSvgDeclarationsCommentsAndLocalFragmentReferences() {
        byte[] svg = TestImages.svg(16, 10,
            "<defs><rect id='shape' width='16' height='10' fill='#2455aa'/></defs><use xlink:href='#shape'/>");
        String src = TestImages.dataUri(("<?xml version='1.0'?><!-- sample -->"
            + new String(svg, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        assertThat(resolver.resolve(src, null, warnings, 5000)).isNotNull();
        assertThat(warnings).isEmpty();
    }

    @Test
    void blocksSvgExternalRequestsInsteadOfReturningASeeminglySuccessfulBrokenImage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pixel.png", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String src = TestImages.dataUri(TestImages.svg(16, 10,
                "<image width='16' height='10' xlink:href='http://127.0.0.1:"
                    + server.getAddress().getPort() + "/pixel.png'/>"));
            assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
            assertWarning("IMAGE_CONVERSION_FAILED", src);
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///does-not-exist'>]><svg xmlns='http://www.w3.org/2000/svg'>&x;</svg>",
        "<svg xmlns='http://www.w3.org/2000/svg'><broken>",
        "<html>not an SVG</html>"
    })
    void rejectsUnsafeOrMalformedSvg(String xml) {
        String src = TestImages.dataUri(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
        assertWarning("IMAGE_INVALID_DATA", src);
    }

    @Test
    void rejectsOversizedLocalFilesWithoutReadingThem() throws Exception {
        Path file = directory.resolve("large.png");
        try (var output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            output.position(ImageResolver.MAX_IMAGE_BYTES);
            output.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertThat(resolver.resolveToBytes("large.png", new ImageResolver.LocalImagePaths(directory, directory),
            warnings, 5000)).isNull();
        assertWarning("IMAGE_TOO_LARGE", "large.png");
    }

    @Test
    void rejectsOversizedBase64BeforeDecodingAndBoundsItsWarningTarget() {
        int encodedLimit = 4 * ((ImageResolver.MAX_IMAGE_BYTES + 2) / 3);
        String src = "data:image/png;base64," + "A".repeat(encodedLimit + 1);
        assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
        assertWarning("IMAGE_TOO_LARGE", src);
        assertThat(warnings.get(0).getTarget()).hasSize(256).endsWith("...");
    }

    @Test
    void acceptsExactlyFiftyMegabytesOfBase64Data() {
        int encodedLimit = 4 * ((ImageResolver.MAX_IMAGE_BYTES + 2) / 3);
        String src = "data:image/png;base64," + "A".repeat(encodedLimit - 1) + "=";
        assertThat(resolver.resolveToBytes(src, null, warnings, 5000)).hasSize(ImageResolver.MAX_IMAGE_BYTES);
        assertThat(warnings).isEmpty();
    }

    @Test
    void rejectsDecodedDataOneByteOverTheLimitEvenWhenItsEncodedLengthFits() {
        int encodedLimit = 4 * ((ImageResolver.MAX_IMAGE_BYTES + 2) / 3);
        String src = "data:image/png;base64," + "A".repeat(encodedLimit);
        assertThat(resolver.resolveToBytes(src, null, warnings, 5000)).isNull();
        assertWarning("IMAGE_TOO_LARGE", src);
    }

    @Test
    void acceptsExactlyFiftyMegabytesFromLocalFiles() throws Exception {
        Path file = directory.resolve("at-limit.png");
        try (var output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            output.position(ImageResolver.MAX_IMAGE_BYTES - 1);
            output.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertThat(resolver.resolveToBytes("at-limit.png", new ImageResolver.LocalImagePaths(directory, directory),
            warnings, 5000)).hasSize(ImageResolver.MAX_IMAGE_BYTES);
        assertThat(warnings).isEmpty();
    }

    @Test
    void rejectsOversizedRemoteContentLengthBeforeReadingTheBody() throws Exception {
        String src = "https://images.example.test/large";
        try (MockedStatic<URI> remote = remote(src, new byte[0], "image/png")) {
            when(connection.getContentLengthLong()).thenReturn(ImageResolver.MAX_IMAGE_BYTES + 1L);
            assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
            assertWarning("IMAGE_TOO_LARGE", src);
            verify(connection, never()).getInputStream();
            verify(connection).disconnect();
        }
    }

    @Test
    void enforcesTheRemoteStreamLimitEvenWithAMissingOrIncorrectLengthHeader() throws Exception {
        String src = "https://images.example.test/large";
        try (MockedStatic<URI> remote = remote(src, new byte[ImageResolver.MAX_IMAGE_BYTES + 1], "image/png")) {
            when(connection.getContentLengthLong()).thenReturn(-1L);
            assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
            assertWarning("IMAGE_TOO_LARGE", src);
            verify(connection).disconnect();
        }
    }

    @Test
    void distinguishesDownloadFailureFromInvalidImageData() throws Exception {
        String src = "https://images.example.test/missing";
        try (MockedStatic<URI> remote = remote(src, new byte[0], "image/png")) {
            when(connection.getResponseCode()).thenReturn(404);
            assertThat(resolver.resolve(src, null, warnings, 5000)).isNull();
            assertWarning("IMAGE_FETCH_FAILED", src);
        }
    }

    private MockedStatic<URI> remote(String src, byte[] bytes, String contentType) throws IOException {
        URI uri = mock(URI.class);
        URL url = mock(URL.class);
        connection = mock(HttpURLConnection.class);
        when(uri.getHost()).thenReturn("203.0.113.10");
        when(uri.toURL()).thenReturn(url);
        when(url.openConnection()).thenReturn(connection);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getContentType()).thenReturn(contentType);
        when(connection.getContentLengthLong()).thenReturn((long) bytes.length);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
        MockedStatic<URI> mocked = mockStatic(URI.class, CALLS_REAL_METHODS);
        mocked.when(() -> URI.create(src)).thenReturn(uri);
        return mocked;
    }

    private void assertWarning(String type, String src) {
        assertThat(warnings).singleElement().satisfies(warning -> {
            assertThat(warning.getType()).isEqualTo(type);
            assertThat(warning.getTarget()).isEqualTo(ConvertWarning.imageTarget(src));
        });
    }
}
