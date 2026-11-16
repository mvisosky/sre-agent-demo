package com.example.sre.agent.service;

import com.example.sre.common.model.ExtractedAlertInfo;
import com.example.sre.common.model.IncidentReport;
import com.example.sre.common.model.IncidentReport.InvestigationStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFileReportPublisherTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteMarkdownAndJsonReportFilesToLocalDirectory() throws Exception {
        LocalFileReportPublisher publisher = new LocalFileReportPublisher(tempDir.toString());

        ExtractedAlertInfo alertInfo = new ExtractedAlertInfo(
            "payment-service",
            "prod-us-east-1",
            "P1",
            "High HTTP 5xx Error Rate on /api/v1/payments/charge",
            "HikariPool-1 connection timeout after 5000ms",
            List.of("HTTP 5xx > 30%", "Latency p99 > 5s"),
            List.of("/api/v1/payments/charge")
        );

        IncidentReport draft = new IncidentReport(
            "inc-12345678",
            Instant.parse("2026-11-15T21:00:00Z"),
            alertInfo,
            List.of("payment-service Incident Investigation Playbook (service: payment-service)"),
            List.of(
                new InvestigationStep(1, "Check service health", "getServiceHealth(payment-service)", "Status: DEGRADED"),
                new InvestigationStep(2, "Check DB pool", "getDatabaseConnectionPoolStats(payment-service)", "Active: 20/20")
            ),
            "HikariPool-1 is 100% saturated due to synchronous fraud calls inside @Transactional in v2.14.2.",
            "Deployment v2.14.2 introduced a blocking HTTP call to fraud-detection-service inside @Transactional.",
            List.of(
                "Roll back payment-service from v2.14.2 to v2.14.1 immediately.",
                "Move fraud-detection-service call outside the @Transactional boundary."
            ),
            null,
            null
        );

        IncidentReport published = publisher.publishReport(draft);

        assertThat(published.markdownReportPath()).isNotBlank();
        assertThat(published.jsonReportPath()).isNotBlank();

        Path mdPath = Path.of(published.markdownReportPath());
        Path jsonPath = Path.of(published.jsonReportPath());

        assertThat(Files.exists(mdPath)).isTrue();
        assertThat(Files.exists(jsonPath)).isTrue();

        String mdContent = Files.readString(mdPath);
        assertThat(mdContent)
            .contains("# SRE Incident Investigation Report: `inc-12345678`")
            .contains("payment-service")
            .contains("getDatabaseConnectionPoolStats(payment-service)")
            .contains("Roll back payment-service from v2.14.2 to v2.14.1 immediately.");

        List<String> files = publisher.listReportFiles();
        assertThat(files).hasSize(2);
    }
}
