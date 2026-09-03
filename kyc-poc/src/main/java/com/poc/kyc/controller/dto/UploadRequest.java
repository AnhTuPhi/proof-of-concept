package com.poc.kyc.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record UploadRequest(
        @NotBlank String fileName,
        @Positive long sizeBytes
) {}
