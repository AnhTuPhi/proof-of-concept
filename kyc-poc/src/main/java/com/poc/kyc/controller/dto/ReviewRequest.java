package com.poc.kyc.controller.dto;

import com.poc.kyc.model.ReviewDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReviewRequest(
        @NotNull ReviewDecision decision,
        @NotBlank String reviewerNote,
        @NotBlank String reviewer
) {}
