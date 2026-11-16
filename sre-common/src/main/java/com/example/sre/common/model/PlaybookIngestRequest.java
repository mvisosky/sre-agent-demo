package com.example.sre.common.model;

import jakarta.validation.constraints.NotBlank;

public record PlaybookIngestRequest(
    @NotBlank(message = "serviceName is required") String serviceName,
    @NotBlank(message = "title is required") String title,
    String version,
    @NotBlank(message = "markdownContent is required") String markdownContent
) {
    public PlaybookIngestRequest {
        if (serviceName != null) {
            serviceName = serviceName.trim().toLowerCase();
        }
        if (version == null || version.isBlank()) {
            version = "1.0";
        }
    }
}
