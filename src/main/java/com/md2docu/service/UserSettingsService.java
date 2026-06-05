package com.md2docu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.md2docu.model.UserSettings;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class UserSettingsService {

    private static final Path SETTINGS_PATH = Path.of(
        System.getProperty("user.home"), ".md2docu", "settings.json");

    private final ObjectMapper mapper;
    private volatile UserSettings cached;

    public UserSettingsService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public UserSettings get() {
        if (cached == null) {
            synchronized (this) {
                if (cached == null) cached = load();
            }
        }
        return cached;
    }

    public synchronized UserSettings save(UserSettings settings) throws IOException {
        clamp(settings);
        Files.createDirectories(SETTINGS_PATH.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(SETTINGS_PATH.toFile(), settings);
        cached = settings;
        return settings;
    }

    public synchronized UserSettings reset() throws IOException {
        UserSettings defaults = new UserSettings();
        Files.createDirectories(SETTINGS_PATH.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(SETTINGS_PATH.toFile(), defaults);
        cached = defaults;
        return defaults;
    }

    private UserSettings load() {
        if (!Files.exists(SETTINGS_PATH)) return new UserSettings();
        try {
            UserSettings loaded = mapper.readValue(SETTINGS_PATH.toFile(), UserSettings.class);
            clamp(loaded);
            return loaded;
        } catch (IOException e) {
            return new UserSettings();
        }
    }

    private void clamp(UserSettings s) {
        s.setBodyFontSizePt(clampInt(s.getBodyFontSizePt(), 6, 72));
        s.setCodeFontSizePt(clampInt(s.getCodeFontSizePt(), 6, 36));
        s.setLineHeight(Math.max(1.0, Math.min(3.0, s.getLineHeight())));
        s.setH1FontSizePt(clampInt(s.getH1FontSizePt(), 8, 72));
        s.setH2FontSizePt(clampInt(s.getH2FontSizePt(), 8, 60));
        s.setH3FontSizePt(clampInt(s.getH3FontSizePt(), 8, 48));
        s.setH4FontSizePt(clampInt(s.getH4FontSizePt(), 8, 36));
        s.setH5FontSizePt(clampInt(s.getH5FontSizePt(), 8, 24));
        s.setH6FontSizePt(clampInt(s.getH6FontSizePt(), 8, 24));
        s.setMarginTopMm(clampInt(s.getMarginTopMm(), 5, 100));
        s.setMarginBottomMm(clampInt(s.getMarginBottomMm(), 5, 100));
        s.setMarginLeftMm(clampInt(s.getMarginLeftMm(), 5, 100));
        s.setMarginRightMm(clampInt(s.getMarginRightMm(), 5, 100));
        if (s.getBodyFontFamily() == null || s.getBodyFontFamily().isBlank()) {
            s.setBodyFontFamily(new UserSettings().getBodyFontFamily());
        }
    }

    private int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
