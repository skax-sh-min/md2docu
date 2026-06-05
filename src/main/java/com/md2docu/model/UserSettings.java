package com.md2docu.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
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
    private int    marginTopMm    = 20;
    private int    marginBottomMm = 20;
    private int    marginLeftMm   = 25;
    private int    marginRightMm  = 25;
}
