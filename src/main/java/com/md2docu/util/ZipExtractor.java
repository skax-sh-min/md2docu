package com.md2docu.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ZipExtractor {

    @Value("${app.zip.max-extract-bytes:209715200}")
    private long maxExtractBytes;

    public record ExtractionResult(Path baseDir, Path mainMdFile) {}

    public ExtractionResult extract(byte[] zipBytes) throws IOException {
        Path tempDir = Files.createTempDirectory("md2docu-");
        try {
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                long totalBytes = 0;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        zis.closeEntry();
                        continue;
                    }
                    Path outPath = tempDir.resolve(entry.getName()).normalize();
                    // ZIP Slip 방지
                    if (!outPath.startsWith(tempDir)) {
                        zis.closeEntry();
                        continue;
                    }
                    Files.createDirectories(outPath.getParent());
                    byte[] data = readLimited(zis, maxExtractBytes - totalBytes);
                    totalBytes += data.length;
                    Files.write(outPath, data);
                    zis.closeEntry();
                }
            }
            Path mainMd = findMainMdFile(tempDir);
            return new ExtractionResult(tempDir, mainMd);
        } catch (IOException e) {
            cleanup(tempDir);
            throw e;
        }
    }

    private byte[] readLimited(ZipInputStream zis, long remaining) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        long written = 0;
        while ((n = zis.read(chunk)) != -1) {
            written += n;
            if (written > remaining) {
                throw new IOException(
                    "ZIP 압축 해제 크기가 최대 허용량(" + (maxExtractBytes / 1024 / 1024) + "MB)을 초과했습니다.");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private Path findMainMdFile(Path dir) throws IOException {
        List<Path> mdFiles = new ArrayList<>();
        Files.walk(dir)
             .filter(p -> p.toString().toLowerCase().endsWith(".md"))
             .forEach(mdFiles::add);

        if (mdFiles.isEmpty()) return null;
        // 경로 깊이가 얕은 파일 우선 (루트 레벨의 .md 파일)
        mdFiles.sort(Comparator.comparingInt(p -> dir.relativize(p).getNameCount()));
        return mdFiles.get(0);
    }

    public void cleanup(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> {
                     try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                 });
        } catch (IOException ignored) {}
    }
}
