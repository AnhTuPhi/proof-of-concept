package com.vndirect.poc.merge.service;

public record MergeRequest(
    Long sourceUserId,
    Long targetUserId,
    String winningEmail,
    String winningDisplayName,
    String winningPhone
) {}
