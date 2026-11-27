package com.md2docu.service;

import com.md2docu.controller.SettingsController;
import com.md2docu.model.UserSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SettingsControllerTest {
    @TempDir Path directory;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private Path file;
    private UserSettingsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        file = directory.resolve("settings.json");
        service = new UserSettingsService(mapper, file);
        mvc = MockMvcBuilders.standaloneSetup(new SettingsController(service, new FontService())).build();
    }

    @Test
    void restoresServerDefaultsToTheFormWithoutPersistingUntilSave() throws Exception {
        var custom = new UserSettings();
        custom.setBodyFontSizePt(17);
        service.save(custom);
        byte[] before = Files.readAllBytes(file);
        mvc.perform(get("/api/settings/defaults")).andExpect(status().isOk())
            .andExpect(jsonPath("$.bodyFontSizePt").value(11))
            .andExpect(jsonPath("$.marginLeftMm").value(15))
            .andExpect(jsonPath("$.marginRightMm").value(15))
            .andExpect(jsonPath("$.marginTopMm").value(14))
            .andExpect(jsonPath("$.marginBottomMm").value(14));
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(service.get()).isEqualTo(custom);
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(new UserSettings())))
            .andExpect(status().isOk());
        mvc.perform(get("/api/settings")).andExpect(status().isOk())
            .andExpect(jsonPath("$.bodyFontSizePt").value(11));
        assertThat(new UserSettingsService(mapper, file).get()).isEqualTo(new UserSettings());
    }

    @Test
    void savesThenReopensTheExactNormalizedServerValues() throws Exception {
        var custom = new UserSettings();
        custom.setBodyFontSizePt(17);
        custom.setLineHeight(2.25);
        custom.setMarginLeftMm(10);
        custom.setMarginRightMm(20);
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(custom)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.lineHeight").value(2.25));
        mvc.perform(get("/api/settings")).andExpect(status().isOk())
            .andExpect(jsonPath("$.bodyFontSizePt").value(17))
            .andExpect(jsonPath("$.marginLeftMm").value(10))
            .andExpect(jsonPath("$.marginRightMm").value(20));
        assertThat(new UserSettingsService(mapper, file).get()).isEqualTo(custom);
        mvc.perform(post("/api/settings/reset")).andExpect(status().isOk())
            .andExpect(jsonPath("$.marginTopMm").value(14));
        assertThat(new UserSettingsService(mapper, file).get()).isEqualTo(new UserSettings());
    }

    @ParameterizedTest
    @CsvSource({"16,15,14,14,A4", "15,15,15,15,LETTER"})
    void rejectsInvalidContentAreasWithA400AndPreservesSavedValues(
            int left, int right, int top, int bottom, String paper) throws Exception {
        service.save(new UserSettings());
        byte[] before = Files.readAllBytes(file);
        var invalid = new UserSettings();
        invalid.setMarginLeftMm(left);
        invalid.setMarginRightMm(right);
        invalid.setMarginTopMm(top);
        invalid.setMarginBottomMm(bottom);
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(invalid)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString(paper)))
            .andExpect(jsonPath("$.error", containsString("180")))
            .andExpect(jsonPath("$.error", containsString("250")));
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(service.get()).isEqualTo(new UserSettings());
    }

    @Test
    void retainsLegacyValuesUntilTheUserExplicitlySavesValidDefaults() throws Exception {
        String legacy = "{\"marginTopMm\":20,\"marginBottomMm\":20,\"marginLeftMm\":25,\"marginRightMm\":25}";
        Files.writeString(file, legacy);
        mvc.perform(get("/api/settings")).andExpect(status().isOk())
            .andExpect(jsonPath("$.marginLeftMm").value(25));
        assertThat(Files.readString(file)).isEqualTo(legacy);
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(legacy))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        assertThat(Files.readString(file)).isEqualTo(legacy);
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(new UserSettings())))
            .andExpect(status().isOk());
        assertThat(new UserSettingsService(mapper, file).get()).isEqualTo(new UserSettings());
    }
}
