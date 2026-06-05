package com.md2docu.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FontService {

    @Value("${app.pdf.font.paths:}")
    private String fontPathsRaw;

    public record FontEntry(String path, String family) {}

    private List<FontEntry> availableFonts = List.of();

    @PostConstruct
    public void detectFonts() {
        if (fontPathsRaw.isBlank()) {
            return;
        }
        availableFonts = Arrays.stream(fontPathsRaw.split(","))
            .map(String::trim)
            .filter(e -> e.contains("|"))
            .map(e -> {
                String[] parts = e.split("\\|", 2);
                return new FontEntry(parts[0].trim(), parts[1].trim());
            })
            .filter(entry -> new File(entry.path()).exists())
            .collect(Collectors.toUnmodifiableList());
    }

    public List<FontEntry> getAvailableFonts() {
        return availableFonts;
    }

    public List<String> getAvailableFamilies() {
        return availableFonts.stream()
            .map(FontEntry::family)
            .distinct()
            .collect(Collectors.toList());
    }
}
