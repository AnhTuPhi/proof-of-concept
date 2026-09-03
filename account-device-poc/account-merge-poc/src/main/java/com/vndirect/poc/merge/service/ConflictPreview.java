package com.vndirect.poc.merge.service;

import java.util.List;

public record ConflictPreview(
    Long sourceUserId,
    Long targetUserId,
    List<FieldConflict> conflicts,
    int sourceRows,
    int targetRows
) {
    public record FieldConflict(String field, String sourceValue, String targetValue) {}
}
