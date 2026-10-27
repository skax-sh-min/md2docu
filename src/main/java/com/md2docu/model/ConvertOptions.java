package com.md2docu.model;

import lombok.Data;

@Data
public class ConvertOptions {
    private String pageSize = "A4";
    private boolean includeImages = true;
    /** keep: 링크 유지, ignore: 링크 제거 후 콘텐츠 유지, warn: 로컬 첨부 미포함 표시 */
    private String linkStrategy = "keep";
    private int remoteImageTimeout = 5000;
    private boolean generateToc = false;
    private boolean numberHeadings = false;
}
