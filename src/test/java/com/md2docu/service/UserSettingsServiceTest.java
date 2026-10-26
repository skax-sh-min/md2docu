package com.md2docu.service;

import com.md2docu.model.UserSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
    void translatesJacksonWriteErrorsAndPreservesCachedSettings() throws IOException {
        UserSettingsService service = new UserSettingsService(mapper, settingsPath);
        UserSettings original = service.get();
        Files.createDirectory(settingsPath);

        assertThatExceptionOfType(IOException.class)
            .isThrownBy(() -> service.save(new UserSettings()))
            .withCauseInstanceOf(JacksonException.class);
        assertThat(service.get()).isSameAs(original);
    }
}
