package com.example.sre.agent.service;

import com.example.sre.common.model.AgentInvestigationResult;
import com.example.sre.common.model.EmailWebhookRequest;
import com.example.sre.common.model.ExtractedAlertInfo;
import com.example.sre.common.model.IncidentReport;
import com.example.sre.common.model.IncidentReport.InvestigationStep;
import com.example.sre.common.model.PlaybookChunkResult;
import com.example.sre.playbook.service.PlaybookRagService;
import com.example.sre.tools.ObservabilityTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SreInvestigationAgentServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldOrchestrateEmailExtractionPlaybookRagInvestigationAndLocalFilePublishing() throws Exception {
        ObservabilityTools observabilityTools = new ObservabilityTools();
        LocalFileReportPublisher publisher = new LocalFileReportPublisher(tempDir.toString());

        EmailWebhookRequest email = new EmailWebhookRequest(
            "msg-001",
            "alerts@pagerduty.example.com",
            "[P1] payment-service HTTP 5xx error rate > 30% in prod-us-east-1",
            "Alert triggered for payment-service on endpoint POST /api/v1/payments/charge. HikariPool-1 connection timeouts detected.",
            Instant.parse("2026-11-15T21:00:00Z"),
            Map.of("X-Alert-Source", "PagerDuty")
        );

        ExtractedAlertInfo extracted = new ExtractedAlertInfo(
            "payment-service",
            "prod-us-east-1",
            "P1",
            "payment-service HTTP 5xx error rate > 30%",
            "HikariPool-1 connection timeouts on /api/v1/payments/charge",
            List.of("HTTP 5xx > 30%", "HikariPool-1 timeouts"),
            List.of("/api/v1/payments/charge")
        );

        EmailAlertExtractorService fakeExtractor = new EmailAlertExtractorService(null) {
            @Override
            public ExtractedAlertInfo extractAlertInfo(EmailWebhookRequest req) {
                return extracted;
            }
        };

        PlaybookRagService fakeRagService = new PlaybookRagService(null) {
            @Override
            public List<PlaybookChunkResult> queryPlaybooksForService(String serviceName, String symptomQuery, int topK) {
                return List.of(new PlaybookChunkResult(
                    "chunk-1",
                    serviceName,
                    "payment-service Incident Investigation Playbook",
                    "Step 1: getServiceHealth. Step 2: getDatabaseConnectionPoolStats.",
                    Map.of("serviceName", serviceName)
                ));
            }
        };

        SreInvestigationAgentService agentService = new SreInvestigationAgentService(
            fakeExtractor,
            fakeRagService,
            observabilityTools,
            null,
            publisher
        ) {
            @Override
            protected AgentInvestigationResult executeAgentInvestigation(String investigationPrompt) {
                // Simulate the LLM calling the tools during its investigation
                observabilityTools.getServiceHealth("payment-service");
                observabilityTools.getDatabaseConnectionPoolStats("payment-service");
                observabilityTools.getRecentDeployments("payment-service");

                return new AgentInvestigationResult(
                    observabilityTools.getRecordedSteps(),
                    "payment-service HikariPool-1 is 100% saturated (20/20 active, 148 waiting threads) after deployment v2.14.2.",
                    "Release v2.14.2 added synchronous fraud scoring inside @Transactional, holding DB connections for ~3.9s per request.",
                    List.of(
                        "Immediately roll back payment-service to v2.14.1.",
                        "Move synchronous fraud-detection-service HTTP call outside @Transactional."
                    )
                );
            }
        };

        IncidentReport report = agentService.handleAlertEmail(email);

        assertThat(report.incidentId()).startsWith("inc-");
        assertThat(report.alertInfo().serviceName()).isEqualTo("payment-service");
        assertThat(report.matchedPlaybooks()).containsExactly("payment-service Incident Investigation Playbook (service: payment-service)");
        assertThat(report.stepsTaken()).hasSize(3);
        assertThat(report.stepsTaken().get(0).toolUsed()).isEqualTo("getServiceHealth(payment-service)");
        assertThat(report.stepsTaken().get(1).toolUsed()).isEqualTo("getDatabaseConnectionPoolStats(payment-service)");
        assertThat(report.stepsTaken().get(2).toolUsed()).isEqualTo("getRecentDeployments(payment-service)");
        assertThat(report.suggestedCourseOfAction()).hasSize(2);
        assertThat(Files.exists(Path.of(report.markdownReportPath()))).isTrue();
        assertThat(Files.exists(Path.of(report.jsonReportPath()))).isTrue();
    }
}
