package com.demo.resilience.downstream;

public class DownstreamUnavailableException extends RuntimeException {
    public DownstreamUnavailableException(String message) {
        super(message);
    }
}
