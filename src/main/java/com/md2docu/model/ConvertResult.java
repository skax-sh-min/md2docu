package com.md2docu.model;

import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class ConvertResult {
    private String jobId;
    private String downloadUrl;
    private byte[] fileBytes;
    private String fileName;
    private String contentType;
    private List<ConvertWarning> warnings;
    private Instant expiresAt;
}
