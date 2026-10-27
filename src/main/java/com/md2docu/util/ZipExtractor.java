package com.md2docu.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.EOFException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipException;

@Component
public class ZipExtractor {

    private static final Logger log = Logger.getLogger(ZipExtractor.class.getName());

    @Value("${app.zip.max-extract-bytes:209715200}")
    private long maxExtractBytes;

    public record ExtractionResult(Path baseDir, Path mainMdFile) {}

    public ExtractionResult extract(byte[] zipBytes) throws IOException {
        if (zipBytes.length < 4 || zipBytes[0] != 'P' || zipBytes[1] != 'K'
                || !((zipBytes[2] == 3 && zipBytes[3] == 4) || (zipBytes[2] == 5 && zipBytes[3] == 6))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 ZIP 파일이 아닙니다.");
        }
        Path tempDir = Files.createTempDirectory("md2docu-");
        boolean completed = false;
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
                    long written = copyLimited(zis, outPath, maxExtractBytes - totalBytes);
                    totalBytes += written;
                    zis.closeEntry();
                }
            }
            Path mainMd = findMainMdFile(tempDir);
            completed = true;
            return new ExtractionResult(tempDir, mainMd);
        } catch (ZipException | EOFException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ZIP 파일이 손상되었습니다.", e);
        } finally {
            if (!completed) cleanup(tempDir);
        }
    }

    private long copyLimited(ZipInputStream zis, Path outPath, long remaining) throws IOException {
        try (OutputStream out = Files.newOutputStream(outPath)) {
            byte[] chunk = new byte[8192];
            int n;
            long written = 0;
            while ((n = zis.read(chunk)) != -1) {
                written += n;
                if (written > remaining) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "ZIP 압축 해제 크기가 최대 허용량(" + (maxExtractBytes / 1024 / 1024) + "MB)을 초과했습니다.");
                }
                out.write(chunk, 0, n);
            }
            return written;
        }
    }

    private Path findMainMdFile(Path dir) throws IOException {
        List<Path> mdFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(p -> Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                  .forEach(mdFiles::add);
        }
        if (mdFiles.isEmpty()) return null;
        // 경로 깊이가 얕은 파일 우선 (루트 레벨의 .md 파일)
        mdFiles.sort(Comparator.comparingInt(p -> dir.relativize(p).getNameCount()));
        return mdFiles.get(0);
    }

    public void cleanup(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> {
                      try {
                          Files.deleteIfExists(p);
                      } catch (IOException e) {
                          log.warning("임시 파일 삭제 실패: " + p + " — " + e.getMessage());
                      }
                  });
        } catch (IOException | UncheckedIOException e) {
            log.warning("임시 디렉터리 정리 실패: " + dir + " — " + e.getMessage());
        }
    }
}
