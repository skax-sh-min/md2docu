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
        return imageWarning("IMAGE_FETCH_FAILED", url, "원격 이미지를 가져올 수 없습니다: ");
    }

    public static ConvertWarning imageNotFound(String path) {
        return imageWarning("IMAGE_NOT_FOUND", path, "이미지 파일을 찾을 수 없습니다: ");
    }

    public static ConvertWarning imageInvalidData(String source) {
        return imageWarning("IMAGE_INVALID_DATA", source, "이미지 데이터가 올바르지 않습니다: ");
    }

    public static ConvertWarning imageUnsupportedFormat(String source) {
        return imageWarning("IMAGE_UNSUPPORTED_FORMAT", source, "지원하지 않는 이미지 형식입니다: ");
    }

    public static ConvertWarning imageTooLarge(String source) {
        return imageWarning("IMAGE_TOO_LARGE", source, "이미지는 50MB 및 600만 픽셀 이하여야 합니다: ");
    }

    public static ConvertWarning imageConversionFailed(String source) {
        return imageWarning("IMAGE_CONVERSION_FAILED", source, "이미지를 PNG로 변환할 수 없습니다: ");
    }

    public static ConvertWarning imageEmbedFailed(String source) {
        return imageWarning("IMAGE_EMBED_FAILED", source, "문서에 이미지를 삽입할 수 없습니다: ");
    }

    public static String imageTarget(String source) {
        if (source == null) return "";
        return source.length() <= 256 ? source : source.substring(0, 253) + "...";
    }

    private static ConvertWarning imageWarning(String type, String source, String message) {
        String target = imageTarget(source);
        return new ConvertWarning(type, target, message + target);
    }

    public static ConvertWarning attachmentNotFound(String path) {
        return new ConvertWarning("ATTACHMENT_NOT_FOUND", path, "첨부파일을 찾을 수 없습니다: " + path);
    }

    public static ConvertWarning unsupportedResource(String target) {
        return new ConvertWarning("UNSUPPORTED_RESOURCE", target, "지원하지 않는 리소스 주소입니다: " + target);
    }

    public static ConvertWarning hyperlinkUnresolved(String target) {
        return new ConvertWarning("HYPERLINK_UNRESOLVED", target, "문서 안에서 링크 대상을 찾을 수 없습니다: " + target);
    }
}
