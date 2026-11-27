package com.md2docu.util;

import com.md2docu.model.ConvertWarning;
import com.md2docu.util.ImageResolver.LocalImagePaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageResolverTest {

    @TempDir
    Path temporary;

    private final ImageResolver resolver = new ImageResolver();
    private final List<ConvertWarning> warnings = new ArrayList<>();
    private LocalImagePaths paths;

    @BeforeEach
    void setUp() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("archive"));
        Path documentDir = Files.createDirectories(root.resolve("docs"));
        paths = new LocalImagePaths(root, documentDir);
        Files.createDirectories(root.resolve("images"));
        Files.createDirectories(documentDir.resolve("images"));
        Files.writeString(root.resolve("images").resolve("test.png"), "root");
        Files.writeString(documentDir.resolve("images").resolve("test.png"), "document");
        Files.writeString(documentDir.resolve("images").resolve("test &.png"), "encoded");
        Files.writeString(documentDir.resolve("images").resolve("test%20&.png"), "percent");
        Files.writeString(temporary.resolve("outside.png"), "outside");
    }

    @ParameterizedTest
    @CsvSource({
        "images/test.png, document",
        "./images/test.png, document",
        "../images/test.png, root",
        "/images/test.png, root",
        "%2e%2e/images/test.png, root",
        "images/test%20%26.png, encoded",
        "images/test%2520%26.png, percent",
        "images/test.png?v=1&size=2, document"
    })
    void resolvesAndDecodesWithinTheArchive(String reference, String expected) {
        assertThat(resolver.resolveToBytes(reference, paths, warnings, 5000))
            .isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "../../outside.png",
        "/../outside.png",
        "%2e%2e/%2e%2e/outside.png",
        "missing.png",
        "images/test%GG.png",
        "images/test%00.png"
    })
    void reportsInvalidMissingAndOutOfBoundsImages(String reference) {
        assertRejected(reference, paths);
    }

    @Test
    void rejectsAbsoluteFileUrisEvenWhenTheFileExists() {
        assertRejected(temporary.resolve("outside.png").toUri().toString(), paths);
    }

    @Test
    void rejectsDocumentDirectoriesOutsideTheArchive() {
        assertRejected("outside.png", new LocalImagePaths(paths.root(), temporary));
    }

    private void assertRejected(String reference, LocalImagePaths imagePaths) {
        assertThat(resolver.resolveToBytes(reference, imagePaths, warnings, 5000)).isNull();
        assertThat(warnings).singleElement().satisfies(warning -> {
            assertThat(warning.getType()).isEqualTo("IMAGE_NOT_FOUND");
            assertThat(warning.getTarget()).isEqualTo(reference);
        });
    }
}
