package com.md2docu.service;

import com.md2docu.model.UserSettings;
import com.md2docu.util.PageLayout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class UserSettingsService {

    private static final Logger log = Logger.getLogger(UserSettingsService.class.getName());
    private static final Path DEFAULT_SETTINGS_PATH = Path.of(
        System.getProperty("user.home"), ".md2docu", "settings.json");

    private final ObjectMapper mapper;
    private final Path settingsPath;
    private volatile UserSettings cached;

    @Autowired
    public UserSettingsService(ObjectMapper mapper) {
        this(mapper, DEFAULT_SETTINGS_PATH);
    }

    UserSettingsService(ObjectMapper mapper, Path settingsPath) {
        this.mapper = mapper;
        this.settingsPath = settingsPath;
    }

    public UserSettings get() {
        UserSettings current = cached;
        if (current == null) {
            synchronized (this) {
                if (cached == null) cached = load();
                current = cached;
            }
        }
        return new UserSettings(current);
    }

    public synchronized UserSettings save(UserSettings settings) throws IOException {
        UserSettings snapshot = new UserSettings(settings);
        clamp(snapshot);
        PageLayout.validateAll(snapshot);
        write(snapshot);
        cached = snapshot;
        return new UserSettings(snapshot);
    }

    public synchronized UserSettings reset() throws IOException {
        UserSettings defaults = new UserSettings();
        write(defaults);
        cached = defaults;
        return new UserSettings(defaults);
    }

    private UserSettings load() {
        if (Files.notExists(settingsPath)) return new UserSettings();
        try {
            UserSettings loaded = mapper.readValue(settingsPath.toFile(), UserSettings.class);
            if (loaded == null) throw new IOException("설정 파일은 JSON 객체여야 합니다.");
            clamp(loaded);
            return loaded;
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            log.log(Level.WARNING, "출력 설정을 불러올 수 없습니다.", e);
            IOException cause = e instanceof IOException io ? io : new IOException(e);
            throw new UncheckedIOException("출력 설정을 불러올 수 없습니다.", cause);
        }
    }

    private void write(UserSettings settings) throws IOException {
        Path target = settingsPath.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "settings-", ".tmp");
        boolean committed = false;
        try {
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), settings);
            } catch (JacksonException e) {
                throw new IOException("출력 설정을 저장할 수 없습니다.", e);
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            committed = true;
        } finally {
            if (!committed) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.log(Level.WARNING, "실패한 설정 저장의 임시 파일을 정리할 수 없습니다.", e);
                }
            }
        }
    }

    private void clamp(UserSettings s) {
        if (!Double.isFinite(s.getLineHeight())) {
            throw new IllegalArgumentException("줄 간격은 유한한 숫자여야 합니다.");
        }
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
