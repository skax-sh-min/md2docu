package com.md2docu.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ConvertWarning {
    private String type;
    private String target;
    private String message;

    public static ConvertWarning imageFetchFailed(String url) {
        return new ConvertWarning("IMAGE_FETCH_FAILED", url, "원격 이미지를 가져올 수 없습니다: " + url);
    }

    public static ConvertWarning imageNotFound(String path) {
        return new ConvertWarning("IMAGE_NOT_FOUND", path, "이미지 파일을 찾을 수 없습니다: " + path);
    }

    public static ConvertWarning attachmentNotFound(String path) {
        return new ConvertWarning("ATTACHMENT_NOT_FOUND", path, "첨부파일을 찾을 수 없습니다: " + path);
    }
}
