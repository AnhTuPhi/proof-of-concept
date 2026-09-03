package com.poc.upload.model;

public record StoredFile(String fileId, String filename, String contentType, long size, String sha256) {
}
