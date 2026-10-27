package com.md2docu;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

public final class TestImages {
    public enum Format {
        PNG("image/png"), JPEG("image/jpeg"), GIF("image/gif"), BMP("image/x-ms-bmp"),
        SVG("image/png"), WEBP("image/png");

        public final String outputMime;
        Format(String outputMime) { this.outputMime = outputMime; }
    }

    private TestImages() {}

    public static byte[] bytes(Format format) throws IOException {
        return switch (format) {
            case SVG -> svg(16, 10, "<rect width='16' height='10' fill='#2455aa'/>");
            case WEBP -> Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvD0ACAAfQqpJUtf+BiOh/AAA=");
            default -> raster(format.name(), 16, 10);
        };
    }

    public static byte[] raster(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(0x2455AA));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).isTrue();
        return out.toByteArray();
    }

    public static byte[] svg(int width, int height, String content) {
        return ("<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink'"
            + " width='" + width + "' height='" + height + "'>" + content + "</svg>")
            .getBytes(StandardCharsets.UTF_8);
    }

    public static String dataUri(byte[] bytes) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
