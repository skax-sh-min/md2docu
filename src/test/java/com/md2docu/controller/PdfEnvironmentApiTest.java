package com.md2docu.controller;

import com.md2docu.service.ConvertService;
import com.md2docu.service.FontService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PdfEnvironmentApiTest {
    @ParameterizedTest
    @CsvSource({"Windows 11,none,true", "Linux,none,true", "Mac OS X,none,true",
        "Windows 11,latin,true", "Linux,latin,true", "Mac OS X,latin,true",
        "Windows 11,korean,false", "Linux,korean,false", "Mac OS X,korean,false"})
    void reportsActualConfiguredFontCapabilitiesRegardlessOfOs(String os, String state, boolean warning) throws Exception {
        var fonts = mock(FontService.class);
        var faces = state.equals("none") ? List.<FontService.FontInfo>of()
            : List.of(new FontService.FontInfo("Test Family", "TestFace", 400, "normal", state.equals("korean")));
        when(fonts.getStatus()).thenReturn(new FontService.FontStatus(
            Instant.parse("2026-10-27T00:00:00Z"), FontService.KOREAN_SAMPLE, faces, List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new ConvertController(mock(ConvertService.class), fonts)).build();
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", os);
            mvc.perform(get("/api/system/info")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.pdfKoreanWarning").value(warning))
                .andExpect(jsonPath("$.verificationScope").value("configured-fonts-korean-sample"))
                .andExpect(jsonPath("$.pdfFonts.checkedAt").value("2026-10-27T00:00:00Z"))
                .andExpect(jsonPath("$.pdfFonts.fonts").isArray())
                .andExpect(jsonPath("$.pdfFonts.issues").isArray());
        } finally {
            System.setProperty("os.name", original);
        }
    }

    @Test
    void unexpectedProbeFailuresReturnAnErrorInsteadOfAnOsBasedSuccess() throws Exception {
        var fonts = mock(FontService.class);
        when(fonts.getStatus()).thenThrow(new IllegalStateException("Probe failed"));
        var mvc = MockMvcBuilders.standaloneSetup(new ConvertController(mock(ConvertService.class), fonts))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/system/info")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").isString());
    }
}
