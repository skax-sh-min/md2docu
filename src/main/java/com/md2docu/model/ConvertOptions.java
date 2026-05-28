package com.md2docu.model;

import lombok.Data;

@Data
public class ConvertOptions {
    private String pageSize = "A4";
    private boolean includeImages = true;
    /** keep: 링크 유지, ignore: 텍스트만, warn: 텍스트 + 경고 표시 */
    private String linkStrategy = "keep";
    private int remoteImageTimeout = 5000;
    private boolean generateToc = false;
}
