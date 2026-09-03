package com.poc.upload.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class UploadSession {
    public final String uploadId;
    public final String filename;
    public final String contentType;
    public final long totalSize;
    public final int chunkSize;
    public final int totalChunks;
    public final Path tempDir;
    public final Set<Integer> completedChunks = ConcurrentHashMap.newKeySet();
    public final Instant createdAt = Instant.now();
    public volatile String fileId;
    public volatile String status = "IN_PROGRESS";

    public UploadSession(String uploadId, String filename, String contentType,
                         long totalSize, int chunkSize, Path tempDir) {
        this.uploadId = uploadId;
        this.filename = filename;
        this.contentType = contentType;
        this.totalSize = totalSize;
        this.chunkSize = chunkSize;
        this.totalChunks = (int) Math.ceil((double) totalSize / chunkSize);
        this.tempDir = tempDir;
    }
}
