package com.md2docu.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserSettings {
    private String bodyFontFamily = "Malgun Gothic, Noto Sans CJK, Arial, sans-serif";
    private int    bodyFontSizePt = 11;
    private double lineHeight     = 1.6;
    private int    codeFontSizePt = 10;
    private int    h1FontSizePt   = 24;
    private int    h2FontSizePt   = 20;
    private int    h3FontSizePt   = 16;
    private int    h4FontSizePt   = 14;
    private int    h5FontSizePt   = 12;
    private int    h6FontSizePt   = 11;
    private int    marginTopMm    = 14;
    private int    marginBottomMm = 14;
    private int    marginLeftMm   = 15;
    private int    marginRightMm  = 15;

    public UserSettings(UserSettings source) {
        bodyFontFamily = source.bodyFontFamily;
        bodyFontSizePt = source.bodyFontSizePt;
        lineHeight = source.lineHeight;
        codeFontSizePt = source.codeFontSizePt;
        h1FontSizePt = source.h1FontSizePt;
        h2FontSizePt = source.h2FontSizePt;
        h3FontSizePt = source.h3FontSizePt;
        h4FontSizePt = source.h4FontSizePt;
        h5FontSizePt = source.h5FontSizePt;
        h6FontSizePt = source.h6FontSizePt;
        marginTopMm = source.marginTopMm;
        marginBottomMm = source.marginBottomMm;
        marginLeftMm = source.marginLeftMm;
        marginRightMm = source.marginRightMm;
    }
}
