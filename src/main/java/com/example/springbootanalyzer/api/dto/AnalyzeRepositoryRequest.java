package com.example.springbootanalyzer.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnalyzeRepositoryRequest(
        @NotBlank(message = "repositoryUrl is required")
        @Size(max = 2048, message = "repositoryUrl must be 2048 characters or fewer")
        String repositoryUrl,

        @Size(max = 255, message = "branch must be 255 characters or fewer")
        String branch,

        @Valid
        AnalyzeRepositoryCredentials credentials
) {
}
