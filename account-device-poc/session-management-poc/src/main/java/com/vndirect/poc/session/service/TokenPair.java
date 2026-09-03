package com.vndirect.poc.session.service;

public record TokenPair(String accessToken, String refreshToken, String sessionId, long accessExpiresInSeconds) {}
