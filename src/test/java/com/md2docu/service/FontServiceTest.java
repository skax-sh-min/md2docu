package com.md2docu.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class FontServiceTest {
    @TempDir Path directory;
    private Path latin;

    @BeforeEach
    void setUp() throws IOException {
        latin = directory.resolve("latin.ttf");
        try (var source = FontServiceTest.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertThat(source).isNotNull();
            Files.copy(source, latin);
        }
    }

    @Test
    void noConfigurationMeansNoVerifiedFontsNotAnOsGuess() {
        FontService service = detect("");
        assertThat(service.getAvailableFamilies()).isEmpty();
        assertThat(service.getStatus().issues()).isEmpty();
        assertThat(service.getStatus().koreanSampleSupported()).isFalse();
    }

    @Test
    void validatesRealPdfLoadingInsteadOfInferringKoreanSupportFromFamilyNames() {
        FontService service = detect(latin + "|Malgun Gothic");
        assertThat(service.getAvailableFamilies()).containsExactly("Malgun Gothic");
        assertThat(service.getStatus().fonts()).singleElement().satisfies(font -> {
            assertThat(font.name()).isNotBlank();
            assertThat(font.weight()).isEqualTo(400);
            assertThat(font.style()).isEqualTo("normal");
            assertThat(font.koreanSampleSupported()).isFalse();
        });
        assertThat(service.getStatus().koreanSampleSupported()).isFalse();
        assertThat(service.getStatus().issues()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing.ttf", "directory"})
    void excludesMissingFilesAndDirectoriesWithDiagnostics(String name) throws IOException {
        Path path = directory.resolve(name);
        if (name.equals("directory")) Files.createDirectory(path);
        FontService service = detect(path + "|Candidate");
        assertThat(service.getAvailableFamilies()).isEmpty();
        assertThat(service.getStatus().issues()).containsExactly(new FontService.FontIssue("Candidate", "NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "|Family", "path|", "path|Family|extra"})
    void reportsMalformedConfiguration(String raw) {
        FontService service = detect(raw);
        assertThat(service.getStatus().fonts()).isEmpty();
        assertThat(service.getStatus().issues()).singleElement()
            .extracting(FontService.FontIssue::reason).isEqualTo("INVALID_CONFIGURATION");
    }

    @Test
    void reportsCorruptFontFilesButKeepsOtherUsableFonts() throws IOException {
        Path bad = Files.write(directory.resolve("broken.ttf"), new byte[]{1, 2, 3, 4, 5});
        FontService service = detect(bad + "|Broken, " + latin + "|Latin");
        assertThat(service.getAvailableFamilies()).containsExactly("Latin");
        assertThat(service.getStatus().issues()).containsExactly(new FontService.FontIssue("Broken", "LOAD_FAILED"));
        Files.delete(bad);
        Files.delete(latin);
    }

    @Test
    void deduplicatesFamiliesAndResetsDetectionWhenConfigurationIsCleared() {
        FontService service = detect(latin + "|Latin," + latin + "|Latin");
        assertThat(service.getAvailableFamilies()).containsExactly("Latin");
        ReflectionTestUtils.setField(service, "fontPathsRaw", "");
        service.detectFonts();
        assertThat(service.getStatus().fonts()).isEmpty();
        assertThat(service.getStatus().issues()).isEmpty();
    }

    @Test
    void reportsAnEmptyCollectionAsALoadFailure() throws IOException {
        Path empty = Files.write(directory.resolve("empty.ttc"),
            java.nio.ByteBuffer.allocate(12).putInt(0x74746366).putInt(0x00010000).putInt(0).array());
        FontService service = detect(empty + "|Empty");
        assertThat(service.getStatus().fonts()).isEmpty();
        assertThat(service.getStatus().issues()).containsExactly(new FontService.FontIssue("Empty", "LOAD_FAILED"));
        Files.delete(empty);
    }

    @Test
    void reportsDoNotExposeServerPathsAndAreImmutable() {
        FontService service = detect(latin + "|Latin");
        String report = JsonMapper.builder().build().writeValueAsString(service.getStatus());
        assertThat(report).contains("LiberationSans", "koreanSampleSupported").doesNotContain(directory.toString(), "path");
        assertThatThrownBy(() -> service.getStatus().fonts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(service.getStatus().sample()).isEqualTo(FontService.KOREAN_SAMPLE);
        assertThat(service.getStatus().checkedAt()).isNotNull();
    }

    private FontService detect(String raw) {
        FontService service = new FontService();
        ReflectionTestUtils.setField(service, "fontPathsRaw", raw);
        service.detectFonts();
        return service;
    }
}
