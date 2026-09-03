package com.vndirect.poc.merge.service;

import java.util.Map;

public record MergeReport(
    Long survivingUserId,
    Long redirectedUserId,
    Map<String, Integer> rowsReassigned,
    int conflictsResolved
) {}
