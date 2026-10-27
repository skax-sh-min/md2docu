package com.md2docu.util;

import com.md2docu.model.ConvertWarning;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.poi.xwpf.usermodel.Document;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Component
public class ImageResolver {

    static final int MAX_IMAGE_BYTES = 50 * 1024 * 1024;
    static final int MAX_IMAGE_PIXELS = 3_000 * 2_000;

    public record LocalImagePaths(Path root, Path documentDir) {}
    public record ResolvedImage(byte[] bytes, String mimeType, int pictureType, int width, int height) {}
    private record SourceImage(byte[] bytes, String declaredMime) {}

    public String resolveToBase64DataUri(String src, LocalImagePaths paths, List<ConvertWarning> warnings, int timeout) {
        ResolvedImage image = resolveImage(src, paths, warnings, timeout, true);
        return image == null ? null : "data:" + image.mimeType() + ";base64,"
            + Base64.getEncoder().encodeToString(image.bytes());
    }

    public byte[] resolveToBytes(String src, LocalImagePaths paths, List<ConvertWarning> warnings, int timeout) {
        SourceImage source = readSource(src, paths, warnings, timeout);
        return source == null ? null : source.bytes();
    }

    public ResolvedImage resolve(String src, LocalImagePaths paths, List<ConvertWarning> warnings, int timeout) {
        return resolveImage(src, paths, warnings, timeout, false);
    }

    private ResolvedImage resolveImage(String src, LocalImagePaths paths, List<ConvertWarning> warnings,
                                       int timeout, boolean rasterizeGif) {
        SourceImage source = readSource(src, paths, warnings, timeout);
        if (source == null) return null;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source.bytes()))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                if ("image/svg+xml".equals(source.declaredMime()) || looksLikeXml(source.bytes())) {
                    BufferedImage svg = SvgRasterizer.rasterize(source.bytes());
                    return png(svg);
                }
                warnings.add(ConvertWarning.imageUnsupportedFormat(src));
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!List.of("png", "jpeg", "jpg", "gif", "bmp", "webp").contains(format)) {
                    warnings.add(ConvertWarning.imageUnsupportedFormat(src));
                    return null;
                }
                if ("gif".equals(format) && source.bytes().length >= 10) {
                    byte[] gif = source.bytes();
                    checkDimensions((gif[6] & 255) | ((gif[7] & 255) << 8),
                        (gif[8] & 255) | ((gif[9] & 255) << 8));
                }
                checkDimensions(reader.getWidth(0), reader.getHeight(0));
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    warnings.add(ConvertWarning.imageInvalidData(src));
                    return null;
                }
                // PDFBox's GIF import can change palette colors; pass its decoded first frame as PNG.
                if ("webp".equals(format) || (rasterizeGif && "gif".equals(format))) return png(decoded);
                int pictureType = switch (format) {
                    case "png" -> Document.PICTURE_TYPE_PNG;
                    case "gif" -> Document.PICTURE_TYPE_GIF;
                    case "bmp" -> Document.PICTURE_TYPE_BMP;
                    default -> Document.PICTURE_TYPE_JPEG;
                };
                String mime = switch (format) {
                    case "bmp" -> "image/x-ms-bmp";
                    case "jpg" -> "image/jpeg";
                    default -> "image/" + format;
                };
                return new ResolvedImage(source.bytes(), mime, pictureType, decoded.getWidth(), decoded.getHeight());
            } finally {
                reader.dispose();
            }
        } catch (ImageTooLargeException e) {
            warnings.add(ConvertWarning.imageTooLarge(src));
        } catch (TranscoderException e) {
            warnings.add(ConvertWarning.imageConversionFailed(src));
        } catch (IOException | SAXException | IllegalArgumentException | IndexOutOfBoundsException e) {
            warnings.add(ConvertWarning.imageInvalidData(src));
        }
        return null;
    }

    private ResolvedImage png(BufferedImage image) throws IOException {
        checkDimensions(image.getWidth(), image.getHeight());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IllegalStateException("PNG ImageIO writer is not available");
        }
        if (out.size() > MAX_IMAGE_BYTES) throw new ImageTooLargeException();
        return new ResolvedImage(out.toByteArray(), "image/png", Document.PICTURE_TYPE_PNG,
            image.getWidth(), image.getHeight());
    }

    static void checkDimensions(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid image dimensions");
        if ((long) width * height > MAX_IMAGE_PIXELS) throw new ImageTooLargeException();
    }

    static final class ImageTooLargeException extends RuntimeException {}

    private boolean looksLikeXml(byte[] bytes) {
        int i = bytes.length >= 3 && bytes[0] == (byte) 0xEF
            && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE)
                || (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF)
                || (bytes[0] == 0 && bytes[1] == '<'))) return true;
        while (i < bytes.length && (bytes[i] == ' ' || bytes[i] == '\r' || bytes[i] == '\n' || bytes[i] == '\t')) i++;
        return i < bytes.length && bytes[i] == '<';
    }

    private SourceImage readSource(String src, LocalImagePaths paths, List<ConvertWarning> warnings, int timeout) {
        if (src == null || src.isBlank()) {
            warnings.add(ConvertWarning.imageNotFound(src));
            return null;
        }

        if (src.regionMatches(true, 0, "data:", 0, 5)) {
            return readDataUri(src, warnings);
        }

        if (src.regionMatches(true, 0, "http://", 0, 7) || src.regionMatches(true, 0, "https://", 0, 8)) {
            return fetchRemote(src, warnings, timeout);
        }

        if (paths != null) {
            try {
                URI uri = new URI(src);
                if (uri.isAbsolute() || uri.getRawAuthority() != null
                        || uri.getPath() == null || uri.getPath().isEmpty()) {
                    warnings.add(ConvertWarning.imageNotFound(src));
                    return null;
                }
                Path root = paths.root().toAbsolutePath().normalize();
                Path documentDir = paths.documentDir().toAbsolutePath().normalize();
                String relativeSrc = uri.getPath();
                boolean rootRelative = relativeSrc.startsWith("/");
                while (relativeSrc.startsWith("/")) relativeSrc = relativeSrc.substring(1);
                Path imagePath = (rootRelative ? root : documentDir).resolve(relativeSrc).normalize();
                if (!documentDir.startsWith(root) || !imagePath.startsWith(root)) {
                    warnings.add(ConvertWarning.imageNotFound(src));
                    return null;
                }
                Path realImage = imagePath.toRealPath();
                if (!realImage.startsWith(root.toRealPath()) || !Files.isRegularFile(realImage)) {
                    warnings.add(ConvertWarning.imageNotFound(src));
                    return null;
                }
                if (Files.size(realImage) > MAX_IMAGE_BYTES) {
                    warnings.add(ConvertWarning.imageTooLarge(src));
                    return null;
                }
                try (InputStream input = Files.newInputStream(realImage)) {
                    byte[] data = readBounded(input, src, warnings);
                    return data == null ? null : new SourceImage(data, null);
                }
            } catch (IOException | URISyntaxException | IllegalArgumentException e) {
                warnings.add(ConvertWarning.imageNotFound(src));
            }
        } else {
            warnings.add(ConvertWarning.imageNotFound(src));
        }
        return null;
    }

    private SourceImage readDataUri(String src, List<ConvertWarning> warnings) {
        int comma = src.indexOf(',');
        String header = comma < 0 ? "" : src.substring(0, comma).toLowerCase(Locale.ROOT);
        if (!header.startsWith("data:image/") || !header.endsWith(";base64")) {
            warnings.add(ConvertWarning.unsupportedResource(ConvertWarning.imageTarget(src)));
            return null;
        }
        long encodedLength = src.length() - comma - 1L;
        if (encodedLength > 4L * ((MAX_IMAGE_BYTES + 2L) / 3)) {
            warnings.add(ConvertWarning.imageTooLarge(src));
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(src.substring(comma + 1));
            if (bytes.length > MAX_IMAGE_BYTES) {
                warnings.add(ConvertWarning.imageTooLarge(src));
                return null;
            }
            if (bytes.length == 0) {
                warnings.add(ConvertWarning.imageInvalidData(src));
                return null;
            }
            return new SourceImage(bytes, header.substring(5, header.indexOf(';')));
        } catch (IllegalArgumentException e) {
            warnings.add(ConvertWarning.imageInvalidData(src));
            return null;
        }
    }

    private byte[] readBounded(InputStream input, String src, List<ConvertWarning> warnings) throws IOException {
        byte[] bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
        if (bytes.length > MAX_IMAGE_BYTES) {
            warnings.add(ConvertWarning.imageTooLarge(src));
            return null;
        }
        if (bytes.length == 0) {
            warnings.add(ConvertWarning.imageInvalidData(src));
            return null;
        }
        return bytes;
    }

    private SourceImage fetchRemote(String url, List<ConvertWarning> warnings, int timeout) {
        HttpURLConnection conn = null;
        try {
            validateRemoteUrl(url);
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestProperty("User-Agent", "md2docu/1.0");
            if (conn.getResponseCode() == 200) {
                if (conn.getContentLengthLong() > MAX_IMAGE_BYTES) {
                    warnings.add(ConvertWarning.imageTooLarge(url));
                    return null;
                }
                try (InputStream is = conn.getInputStream()) {
                    byte[] data = readBounded(is, url, warnings);
                    String mime = conn.getContentType();
                    if (mime != null) mime = mime.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
                    return data == null ? null : new SourceImage(data, mime);
                }
            }
            warnings.add(ConvertWarning.imageFetchFailed(url));
            return null;
        } catch (IOException | IllegalArgumentException e) {
            warnings.add(ConvertWarning.imageFetchFailed(url));
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void validateRemoteUrl(String url) throws IOException {
        InetAddress addr = InetAddress.getByName(URI.create(url).getHost());
        if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
            throw new IOException("내부 네트워크 주소로의 요청은 허용되지 않습니다: " + url);
        }
    }

}
