package com.md2docu.service;

import com.md2docu.model.UserSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class UserSettingsServiceTest {

    @TempDir
    private Path tempDir;

    private Path settingsPath;
    private JsonMapper mapper;

    @BeforeEach
    void setUp() {
        settingsPath = tempDir.resolve("settings.json");
        mapper = JsonMapper.builder().build();
    }

    @Test
    void usesDefaultsOnlyWhenSettingsFileIsMissing() {
        UserSettingsService service = new UserSettingsService(mapper, settingsPath);

        assertThat(service.get()).isEqualTo(new UserSettings());
        assertThat(settingsPath).doesNotExist();
    }

    @Test
    void savesAndReloadsNormalizedSettingsWithJackson3() throws IOException {
        UserSettings settings = new UserSettings();
        settings.setBodyFontSizePt(99);
        settings.setCodeFontSizePt(1);
        settings.setLineHeight(4.0);
        settings.setMarginLeftMm(0);
        settings.setH5FontSizePt(15);
        UserSettingsService service = new UserSettingsService(mapper, settingsPath);

        UserSettings saved = service.save(settings);

        assertThat(saved.getBodyFontSizePt()).isEqualTo(72);
        assertThat(saved.getCodeFontSizePt()).isEqualTo(6);
        assertThat(saved.getLineHeight()).isEqualTo(3.0);
        assertThat(saved.getMarginLeftMm()).isEqualTo(5);
        assertThat(saved.getH5FontSizePt()).isEqualTo(15);
        assertThat(mapper.readValue(settingsPath.toFile(), UserSettings.class)).isEqualTo(saved);
        assertThat(new UserSettingsService(mapper, settingsPath).get()).isEqualTo(saved);
    }

    @Test
    void resetsPersistedAndCachedSettings() throws IOException {
        UserSettingsService service = new UserSettingsService(mapper, settingsPath);
        UserSettings custom = new UserSettings();
        custom.setBodyFontSizePt(18);
        service.save(custom);

        assertThat(service.reset()).isEqualTo(new UserSettings());
        assertThat(service.get()).isEqualTo(new UserSettings());
        assertThat(new UserSettingsService(mapper, settingsPath).get()).isEqualTo(new UserSettings());
    }

    @Test
    void keepsUnknownSettingCompatibility() throws IOException {
        Files.writeString(settingsPath, "{\"bodyFontSizePt\":17,\"futureOption\":true}");

        UserSettings loaded = new UserSettingsService(mapper, settingsPath).get();

        assertThat(loaded.getBodyFontSizePt()).isEqualTo(17);
        assertThat(loaded.getH1FontSizePt()).isEqualTo(24);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]"})
    void reportsInvalidSettingsInsteadOfSilentlyReplacingThem(String json) throws IOException {
        Files.writeString(settingsPath, json);
        UserSettingsService service = new UserSettingsService(mapper, settingsPath);

        assertThatExceptionOfType(UncheckedIOException.class)
            .isThrownBy(service::get)
            .withCauseInstanceOf(IOException.class);
        assertThat(Files.readString(settingsPath)).isEqualTo(json);
    }

    @Test
    void reportsReadFailureWhenSettingsPathIsDirectory() throws IOException {
        Files.createDirectory(settingsPath);

        assertThatExceptionOfType(UncheckedIOException.class)
            .isThrownBy(() -> new UserSettingsService(mapper, settingsPath).get())
            .withCauseInstanceOf(IOException.class);
    }

    @Test
    void reportsReplacementErrorsAndPreservesCachedSettings() throws IOException {
        UserSettingsService service = new UserSettingsService(mapper, settingsPath);
        UserSettings original = service.get();
        Files.createDirectory(settingsPath);

        assertThatExceptionOfType(IOException.class)
            .isThrownBy(() -> service.save(new UserSettings()));
        assertThat(service.get()).isEqualTo(original);
    }

    @Test
    void neverExposesMutableCachedSettingsOrMutatesTheCallersObject() throws IOException {
        var service = new UserSettingsService(mapper, settingsPath);
        var input = new UserSettings();
        input.setBodyFontSizePt(99);
        UserSettings saved = service.save(input);
        assertThat(input.getBodyFontSizePt()).isEqualTo(99);
        input.setH1FontSizePt(70);
        saved.setBodyFontSizePt(8);
        UserSettings fetched = service.get();
        assertThat(fetched.getBodyFontSizePt()).isEqualTo(72);
        assertThat(fetched.getH1FontSizePt()).isEqualTo(new UserSettings().getH1FontSizePt());
        fetched.setBodyFontSizePt(9);
        assertThat(service.get().getBodyFontSizePt()).isEqualTo(72);
        UserSettings reset = service.reset();
        reset.setBodyFontSizePt(19);
        assertThat(service.get()).isEqualTo(new UserSettings());
    }

    @Test
    void copiesEverySettingsPropertyWithoutSharingTheInstance() {
        UserSettings source = mapper.readValue("""
            {"bodyFontFamily":"Arial","bodyFontSizePt":22,"codeFontSizePt":9,"lineHeight":2.2,
             "h1FontSizePt":36,"h2FontSizePt":28,"h3FontSizePt":22,"h4FontSizePt":18,
             "h5FontSizePt":10,"h6FontSizePt":9,"marginTopMm":10,"marginBottomMm":15,
             "marginLeftMm":10,"marginRightMm":20}
            """, UserSettings.class);
        assertThat(new UserSettings(source)).isEqualTo(source).isNotSameAs(source);
    }

    @Test
    void readsLegacyMarginsWithoutMigratingOrOverwritingTheirFile() throws IOException {
        String old = "{\"marginTopMm\":20,\"marginBottomMm\":20,\"marginLeftMm\":25,\"marginRightMm\":25}";
        Files.writeString(settingsPath, old);
        var service = new UserSettingsService(mapper, settingsPath);
        UserSettings loaded = service.get();
        assertThat(loaded.getMarginLeftMm()).isEqualTo(25);
        assertThat(loaded.getMarginTopMm()).isEqualTo(20);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> service.save(loaded));
        assertThat(Files.readString(settingsPath)).isEqualTo(old);
        assertThat(service.get()).isEqualTo(loaded);
    }

    @ParameterizedTest
    @CsvSource({"16,15,14,14", "15,15,15,15"})
    void rejectsNarrowContentAreasWithoutChangingTheFileOrCache(int left, int right, int top, int bottom) throws IOException {
        var service = new UserSettingsService(mapper, settingsPath);
        UserSettings original = service.save(new UserSettings());
        byte[] before = Files.readAllBytes(settingsPath);
        UserSettings invalid = new UserSettings(original);
        invalid.setMarginLeftMm(left);
        invalid.setMarginRightMm(right);
        invalid.setMarginTopMm(top);
        invalid.setMarginBottomMm(bottom);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> service.save(invalid));
        assertThat(Files.readAllBytes(settingsPath)).isEqualTo(before);
        assertThat(service.get()).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsNonFiniteLineHeights(double height) {
        var service = new UserSettingsService(mapper, settingsPath);
        var invalid = new UserSettings();
        invalid.setLineHeight(height);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> service.save(invalid));
        assertThat(settingsPath).doesNotExist();
        assertThat(service.get()).isEqualTo(new UserSettings());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesTheOldFileAndCacheWhenSerializationFailsPartway(boolean reset) throws IOException {
        var failingMapper = spy(mapper);
        var service = new UserSettingsService(failingMapper, settingsPath);
        var original = new UserSettings();
        original.setBodyFontSizePt(18);
        service.save(original);
        byte[] before = Files.readAllBytes(settingsPath);
        ObjectWriter writer = mock(ObjectWriter.class);
        JacksonException failure = mock(JacksonException.class);
        doReturn(writer).when(failingMapper).writerWithDefaultPrettyPrinter();
        AtomicReference<Path> pending = new AtomicReference<>();
        doAnswer(invocation -> {
            Path file = invocation.getArgument(0, File.class).toPath();
            pending.set(file);
            Files.writeString(file, "{\"partial\":");
            throw failure;
        }).when(writer).writeValue(any(File.class), any());
        assertThatExceptionOfType(IOException.class).isThrownBy(() -> {
            if (reset) service.reset(); else service.save(new UserSettings());
        }).withCause(failure);
        assertThat(Files.readAllBytes(settingsPath)).isEqualTo(before);
        assertThat(service.get()).isEqualTo(original);
        assertThat(pending.get()).doesNotExist();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void neverFallsBackToNonAtomicReplacementOrChangesTheCacheOnMoveFailure(boolean reset, boolean unsupported) throws IOException {
        var service = new UserSettingsService(mapper, settingsPath);
        var original = new UserSettings();
        original.setBodyFontSizePt(18);
        service.save(original);
        byte[] before = Files.readAllBytes(settingsPath);
        AtomicReference<Path> pending = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        IOException failure = unsupported
            ? new AtomicMoveNotSupportedException("pending", "settings", "test")
            : new IOException("injected replacement failure");
        try (MockedStatic<Files> ignored = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("move")
                    && settingsPath.equals(invocation.getArgument(1))) {
                moves.incrementAndGet();
                assertThat(invocation.getRawArguments()[2]).isInstanceOfSatisfying(CopyOption[].class,
                    options -> assertThat(options).containsExactly(
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
                assertThat(Files.readAllBytes(settingsPath)).isEqualTo(before);
                pending.set(invocation.getArgument(0));
                throw failure;
            }
            return invocation.callRealMethod();
        })) {
            assertThatExceptionOfType(IOException.class).isThrownBy(() -> {
                if (reset) service.reset(); else service.save(new UserSettings());
            }).isSameAs(failure);
        }
        assertThat(Files.readAllBytes(settingsPath)).isEqualTo(before);
        assertThat(service.get()).isEqualTo(original);
        assertThat(pending.get()).doesNotExist();
        assertThat(moves).hasValue(1);
    }
}
