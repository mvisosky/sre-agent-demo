package com.example.sre.agent.service;

import com.example.sre.common.model.EmailWebhookRequest;
import com.example.sre.common.model.ExtractedAlertInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AlertFingerprinterTest {

    private final AlertFingerprinter fingerprinter = new AlertFingerprinter();

    @Test
    void shouldProduceIdenticalStage1FingerprintsForDynamicFloodVariations() {
        EmailWebhookRequest email1 = new EmailWebhookRequest(
            "msg-8841",
            "alerts@datadog.example.com",
            "[P1] High HTTP 5xx Error Rate on payment-service pod payment-service-7d9f8b6c4-x2k9p (34.8%, p99=5020ms) #8841",
            "Alert fired at 2026-11-16T09:10:00Z on 10.24.18.92",
            Instant.parse("2026-11-16T09:10:00Z"),
            Map.of()
        );

        EmailWebhookRequest email2 = new EmailWebhookRequest(
            "msg-8849",
            "alerts@datadog.example.com",
            "[P1] High HTTP 5xx Error Rate on payment-service pod payment-service-7d9f8b6c4-m9v4q (41.2%, p99=5310ms) #8849",
            "Alert fired at 2026-11-16T09:10:30Z on 10.24.18.104",
            Instant.parse("2026-11-16T09:10:30Z"),
            Map.of()
        );

        String fp1 = fingerprinter.computeStage1Fingerprint(email1);
        String fp2 = fingerprinter.computeStage1Fingerprint(email2);

        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).contains("payment-service").contains("5xx");
    }

    @Test
    void shouldDistinguishStage1FingerprintsForDifferentServices() {
        EmailWebhookRequest paymentEmail = new EmailWebhookRequest(
            "msg-pay-1",
            "alerts@datadog.example.com",
            "[P1] High HTTP 5xx Error Rate on payment-service",
            "Body",
            Instant.now(),
            Map.of()
        );
        EmailWebhookRequest orderEmail = new EmailWebhookRequest(
            "msg-ord-1",
            "alerts@datadog.example.com",
            "[P1] High HTTP 5xx Error Rate on order-service",
            "Body",
            Instant.now(),
            Map.of()
        );

        assertThat(fingerprinter.computeStage1Fingerprint(paymentEmail))
            .isNotEqualTo(fingerprinter.computeStage1Fingerprint(orderEmail));
    }

    @Test
    void shouldCorrelateStage2AlertsWithOverlappingSymptomsOnSameService() {
        ExtractedAlertInfo active = new ExtractedAlertInfo(
            "payment-service",
            "prod-us-east-1",
            "P1",
            "Payment Service HTTP 5xx & DB Pool Exhaustion",
            "HikariPool-1 connection timeout after 5000ms on /api/v1/payments/charge",
            List.of("HTTP 5xx error rate 34.8%", "HikariPool-1 connection timeout"),
            List.of("/api/v1/payments/charge", "HikariPool-1")
        );

        ExtractedAlertInfo candidate = new ExtractedAlertInfo(
            "payment-service",
            "production",
            "HIGH",
            "Database Connection Pool Saturated on payment-service",
            "HikariPool-1 active connections 20/20 with 148 threads awaiting connection",
            List.of("HikariPool-1 connection timeout", "Slow transaction on /api/v1/payments/charge"),
            List.of("HikariPool-1", "postgres-payments-db")
        );

        assertThat(fingerprinter.isCorrelatedWithActiveIncident(candidate, active, 0.20, false)).isTrue();
    }

    @Test
    void shouldNotCorrelateStage2AlertsAcrossDifferentServices() {
        ExtractedAlertInfo paymentAlert = new ExtractedAlertInfo(
            "payment-service",
            "prod-us-east-1",
            "P1",
            "HikariPool-1 connection timeout",
            "Database connection pool exhausted",
            List.of("HikariPool-1 timeout"),
            List.of("HikariPool-1")
        );

        ExtractedAlertInfo authAlert = new ExtractedAlertInfo(
            "auth-service",
            "prod-us-east-1",
            "P1",
            "HikariPool-1 connection timeout",
            "Database connection pool exhausted",
            List.of("HikariPool-1 timeout"),
            List.of("HikariPool-1")
        );

        assertThat(fingerprinter.isCorrelatedWithActiveIncident(authAlert, paymentAlert, 0.10, true)).isFalse();
    }
}
