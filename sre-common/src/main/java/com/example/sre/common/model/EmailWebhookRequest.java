package com.example.sre.common.model;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;

public record EmailWebhookRequest(
    String messageId,
    @NotBlank(message = "Sender address (from) is required") String from,
    @NotBlank(message = "Email subject is required") String subject,
    @NotBlank(message = "Email body is required") String body,
    Instant receivedAt,
    Map<String, String> headers
) {
    public EmailWebhookRequest {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (headers == null) {
            headers = Map.of();
        }
    }

    public EmailWebhookRequest(String from, String subject, String body, Instant receivedAt) {
        this(null, from, subject, body, receivedAt, Map.of());
    }
}
