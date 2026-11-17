package com.example.sre.common.model;

import com.example.sre.common.security.SafeText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public record EmailWebhookRequest(
    @Size(max = 256) @SafeText String messageId,
    @NotBlank(message = "Sender address (from) is required") @Size(max = 320) @SafeText String from,
    @NotBlank(message = "Email subject is required") @Size(max = 500) @SafeText String subject,
    @NotBlank(message = "Email body is required") @Size(max = 20000, message = "Email body must not exceed 20000 characters") @SafeText String body,
    Instant receivedAt,
    @Size(max = 50) @SafeText Map<String, String> headers
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
