package com.md2docu.service;

import com.md2docu.model.ConvertResult;
import com.md2docu.model.ConvertWarning;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@EnableScheduling
public class ConversionResultStore {
    private final long expirySeconds;
    private final int maxResults;
    private final long maxBytes;
    private final Clock clock;
    private final Map<String, ConvertResult> results = new LinkedHashMap<>();
    private long storedBytes;

    @Autowired
    public ConversionResultStore(
            @Value("${app.convert.temp-expiry-seconds:3600}") long expirySeconds,
            @Value("${app.convert.max-stored-results:500}") int maxResults,
            @Value("${app.convert.max-stored-bytes:268435456}") long maxBytes) {
        this(expirySeconds, maxResults, maxBytes, Clock.systemUTC());
    }

    ConversionResultStore(long expirySeconds, int maxResults, long maxBytes, Clock clock) {
        if (expirySeconds <= 0) throw new IllegalArgumentException("app.convert.temp-expiry-seconds must be positive");
        if (maxResults <= 0) throw new IllegalArgumentException("app.convert.max-stored-results must be positive");
        if (maxBytes <= 0) throw new IllegalArgumentException("app.convert.max-stored-bytes must be positive");
        this.clock = Objects.requireNonNull(clock);
        clock.instant().plusSeconds(expirySeconds);
        this.expirySeconds = expirySeconds;
        this.maxResults = maxResults;
        this.maxBytes = maxBytes;
    }

    public synchronized ConvertResult put(ConvertResult result) {
        byte[] fileBytes = result.getFileBytes();
        int size = fileBytes.length;
        if (size > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "변환 결과 크기가 결과 보관 용량 한도(" + maxBytes + "바이트)를 초과했습니다.");
        }
        ConvertResult snapshot = copy(result, fileBytes);
        Objects.requireNonNull(snapshot.getJobId(), "jobId");
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(expirySeconds);
        snapshot.setExpiresAt(expiresAt);
        evictExpired(now);
        remove(snapshot.getJobId());
        while (results.size() >= maxResults || storedBytes > maxBytes - size) {
            remove(results.keySet().iterator().next());
        }
        results.put(snapshot.getJobId(), snapshot);
        storedBytes += size;
        result.setExpiresAt(expiresAt);
        return result;
    }

    public synchronized ConvertResult get(String jobId) {
        ConvertResult result = find(jobId);
        return result == null ? null : copy(result, result.getFileBytes());
    }

    public synchronized List<ConvertWarning> getWarnings(String jobId) {
        ConvertResult result = find(jobId);
        return result == null ? null : copyWarnings(result.getWarnings());
    }

    @Scheduled(fixedDelayString = "${app.convert.cleanup-interval-ms:60000}")
    public synchronized void evictExpired() {
        evictExpired(clock.instant());
    }

    private ConvertResult find(String jobId) {
        ConvertResult result = results.get(jobId);
        if (result != null && !clock.instant().isBefore(result.getExpiresAt())) {
            remove(jobId);
            return null;
        }
        return result;
    }

    private void evictExpired(Instant now) {
        var iterator = results.values().iterator();
        while (iterator.hasNext()) {
            ConvertResult result = iterator.next();
            if (!now.isBefore(result.getExpiresAt())) {
                storedBytes -= result.getFileBytes().length;
                iterator.remove();
            }
        }
    }

    private void remove(String jobId) {
        ConvertResult removed = results.remove(jobId);
        if (removed != null) storedBytes -= removed.getFileBytes().length;
    }

    private static ConvertResult copy(ConvertResult source, byte[] fileBytes) {
        var copy = new ConvertResult();
        copy.setJobId(source.getJobId());
        copy.setDownloadUrl(source.getDownloadUrl());
        copy.setFileName(source.getFileName());
        copy.setContentType(source.getContentType());
        copy.setFileBytes(fileBytes.clone());
        copy.setWarnings(copyWarnings(source.getWarnings()));
        copy.setExpiresAt(source.getExpiresAt());
        return copy;
    }

    private static List<ConvertWarning> copyWarnings(List<ConvertWarning> warnings) {
        return warnings.stream()
            .map(warning -> new ConvertWarning(warning.getType(), warning.getTarget(), warning.getMessage()))
            .toList();
    }
}
