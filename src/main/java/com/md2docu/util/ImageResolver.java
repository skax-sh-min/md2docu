package com.md2docu.util;

import com.md2docu.model.ConvertWarning;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

@Component
public class ImageResolver {

    public String resolveToBase64DataUri(String src, Path basePath, List<ConvertWarning> warnings, int timeout) {
        try {
            byte[] bytes = resolveToBytes(src, basePath, warnings, timeout);
            if (bytes == null) return null;
            String mime = guessMimeType(src);
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            warnings.add(ConvertWarning.imageFetchFailed(src));
            return null;
        }
    }

    public byte[] resolveToBytes(String src, Path basePath, List<ConvertWarning> warnings, int timeout) {
        if (src == null || src.isBlank()) return null;

        if (src.startsWith("data:")) {
            int comma = src.indexOf(',');
            return comma > 0 ? Base64.getDecoder().decode(src.substring(comma + 1)) : null;
        }

        if (src.startsWith("http://") || src.startsWith("https://")) {
            return fetchRemote(src, warnings, timeout);
        }

        if (basePath != null) {
            String relativeSrc = src;
            while (relativeSrc.startsWith("/")) relativeSrc = relativeSrc.substring(1);
            Path imagePath = basePath.resolve(relativeSrc).normalize();
            if (!imagePath.startsWith(basePath)) {
                warnings.add(ConvertWarning.imageNotFound(src));
                return null;
            }
            if (Files.exists(imagePath)) {
                try {
                    return Files.readAllBytes(imagePath);
                } catch (Exception e) {
                    warnings.add(ConvertWarning.imageNotFound(src));
                }
            } else {
                warnings.add(ConvertWarning.imageNotFound(src));
            }
        }
        return null;
    }

    private byte[] fetchRemote(String url, List<ConvertWarning> warnings, int timeout) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestProperty("User-Agent", "md2docu/1.0");
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    return is.readAllBytes();
                }
            }
            warnings.add(ConvertWarning.imageFetchFailed(url));
            return null;
        } catch (Exception e) {
            warnings.add(ConvertWarning.imageFetchFailed(url));
            return null;
        }
    }

    public String guessMimeType(String src) {
        String lower = src.toLowerCase();
        if (lower.contains(".png"))  return "image/png";
        if (lower.contains(".jpg") || lower.contains(".jpeg")) return "image/jpeg";
        if (lower.contains(".gif"))  return "image/gif";
        if (lower.contains(".svg"))  return "image/svg+xml";
        if (lower.contains(".webp")) return "image/webp";
        return "image/png";
    }

    public int detectPictureType(String src) {
        String lower = src.toLowerCase();
        if (lower.contains(".png"))  return org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG;
        if (lower.contains(".gif"))  return org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_GIF;
        if (lower.contains(".svg"))  return org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG; // SVG는 PNG로 폴백
        return org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG;
    }
}
