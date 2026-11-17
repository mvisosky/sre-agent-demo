package com.example.sre.common.model;

import com.example.sre.common.security.SafeText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PlaybookIngestRequest(
    @NotBlank(message = "serviceName is required") @Size(max = 100) @Pattern(regexp = "(?i)[a-z0-9][a-z0-9._-]*", message = "serviceName must be alphanumeric kebab-case") String serviceName,
    @NotBlank(message = "title is required") @Size(max = 200) @SafeText String title,
    @Size(max = 32) @SafeText String version,
    @NotBlank(message = "markdownContent is required") @Size(max = 100000) @SafeText String markdownContent
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
