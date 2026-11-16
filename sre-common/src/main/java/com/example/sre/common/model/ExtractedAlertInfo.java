package com.example.sre.common.model;

import java.util.List;

public record ExtractedAlertInfo(
    String serviceName,
    String environment,
    String severity,
    String alertTitle,
    String errorSummary,
    List<String> symptoms,
    List<String> affectedEndpointsOrResources
) {
    public ExtractedAlertInfo {
        if (serviceName != null) {
            serviceName = serviceName.trim().toLowerCase();
        }
        if (symptoms == null) {
            symptoms = List.of();
        }
        if (affectedEndpointsOrResources == null) {
            affectedEndpointsOrResources = List.of();
        }
    }
}
