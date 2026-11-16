package com.example.sre.agent.service;

import com.example.sre.common.model.ExtractedAlertInfo;
import com.example.sre.common.model.IncidentReport;
import com.example.sre.common.model.IncidentReport.CorrelatedAlert;
import com.example.sre.common.model.IncidentReport.InvestigationStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
public class LocalFileReportPublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalFileReportPublisher.class);
    private static final DateTimeFormatter FILE_TS_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path reportsDir;
    private final ObjectMapper objectMapper;

    public LocalFileReportPublisher(
            @Value("${sre.agent.reports-dir:./reports}") String reportsDirPath) {
        this.reportsDir = Path.of(reportsDirPath).toAbsolutePath().normalize();
        this.objectMapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();
    }

    public synchronized IncidentReport publishReport(IncidentReport draftReport) {
        try {
            Files.createDirectories(reportsDir);

            Path mdPath;
            Path jsonPath;

            if (draftReport.markdownReportPath() != null && draftReport.jsonReportPath() != null) {
                mdPath = Path.of(draftReport.markdownReportPath());
                jsonPath = Path.of(draftReport.jsonReportPath());
            } else {
                String safeService = sanitizeFileSegment(
                    draftReport.alertInfo() != null ? draftReport.alertInfo().serviceName() : "unknown-service");
                String timestampPart = FILE_TS_FORMAT.format(draftReport.timestamp());
                String baseName = "incident-%s-%s-%s".formatted(
                    timestampPart, safeService, sanitizeFileSegment(draftReport.incidentId()));

                mdPath = reportsDir.resolve(baseName + ".md");
                jsonPath = reportsDir.resolve(baseName + ".json");
            }

            IncidentReport finalReport = draftReport.withFilePaths(mdPath.toString(), jsonPath.toString());

            String markdown = formatMarkdown(finalReport);
            Files.writeString(mdPath, markdown, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            String json = objectMapper.writeValueAsString(finalReport);
            Files.writeString(jsonPath, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.info("Published incident report [incidentId={}, occurrences={}, deduplicated={}] to markdown='{}', json='{}'",
                finalReport.incidentId(), finalReport.occurrenceCount(), finalReport.deduplicated(), mdPath, jsonPath);
            return finalReport;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write incident report to local directory: " + reportsDir, e);
        }
    }

    public List<String> listReportFiles() {
        if (!Files.exists(reportsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(reportsDir)) {
            return stream
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .sorted(Comparator.reverseOrder())
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list report files in " + reportsDir, e);
        }
    }

    public String readReportFile(String filename) {
        Path resolved = reportsDir.resolve(filename).normalize();
        if (!resolved.startsWith(reportsDir) || !Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("Report file not found: " + filename);
        }
        try {
            return Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read report file: " + filename, e);
        }
    }

    public Path getReportsDir() {
        return reportsDir;
    }

    public String formatMarkdown(IncidentReport report) {
        StringBuilder sb = new StringBuilder();
        ExtractedAlertInfo alert = report.alertInfo();

        sb.append("# SRE Incident Investigation Report: `").append(report.incidentId()).append("`\n\n");
        sb.append("- **Timestamp (UTC)**: ").append(report.timestamp()).append("\n");
        sb.append("- **Primary Incident ID**: `").append(report.primaryIncidentId()).append("`\n");
        sb.append("- **Alert Occurrences Correlated**: **").append(report.occurrenceCount()).append("**\n");
        sb.append("- **Last Seen (UTC)**: ").append(report.lastSeenAt()).append("\n");
        if (alert != null) {
            sb.append("- **Service**: `").append(alert.serviceName()).append("`\n");
            sb.append("- **Environment**: `").append(alert.environment()).append("`\n");
            sb.append("- **Severity**: **").append(alert.severity()).append("**\n");
            sb.append("- **Alert Title**: ").append(alert.alertTitle()).append("\n");
            sb.append("- **Error Summary**: ").append(alert.errorSummary()).append("\n");
            if (alert.symptoms() != null && !alert.symptoms().isEmpty()) {
                sb.append("- **Reported Symptoms**:\n");
                for (String symptom : alert.symptoms()) {
                    sb.append("  - ").append(symptom).append("\n");
                }
            }
        }

        if (report.correlatedAlerts() != null && !report.correlatedAlerts().isEmpty()) {
            sb.append("\n## Alert Flood & Deduplication Timeline\n");
            sb.append("| # | Received At (UTC) | Stage | From | Subject |\n");
            sb.append("|---|---|---|---|---|\n");
            int idx = 2;
            for (CorrelatedAlert ca : report.correlatedAlerts()) {
                sb.append("| ").append(idx++).append(" | ")
                  .append(ca.receivedAt()).append(" | `")
                  .append(ca.matchedStage()).append("` | ")
                  .append(ca.from() != null ? ca.from() : "-").append(" | ")
                  .append(ca.subject() != null ? ca.subject().replace("|", "\\|") : "-").append(" |\n");
            }
        }

        sb.append("\n## Playbooks Consulted (RAG)\n");
        if (report.matchedPlaybooks() == null || report.matchedPlaybooks().isEmpty()) {
            sb.append("_No specific service playbook matched._\n");
        } else {
            for (String pb : report.matchedPlaybooks()) {
                sb.append("- ").append(pb).append("\n");
            }
        }

        sb.append("\n## Investigation Steps Taken\n");
        if (report.stepsTaken() == null || report.stepsTaken().isEmpty()) {
            sb.append("_No investigation steps recorded._\n");
        } else {
            for (InvestigationStep step : report.stepsTaken()) {
                sb.append("### Step ").append(step.stepNumber()).append(": ").append(step.action()).append("\n");
                sb.append("- **Tool Executed**: `").append(step.toolUsed()).append("`\n");
                sb.append("- **Observation**:\n\n```text\n")
                  .append(step.observation())
                  .append("\n```\n\n");
            }
        }

        sb.append("## Investigation Findings\n");
        sb.append(report.findings() != null ? report.findings() : "_None_").append("\n\n");

        sb.append("## Root Cause Hypothesis\n");
        sb.append(report.rootCauseHypothesis() != null ? report.rootCauseHypothesis() : "_None_").append("\n\n");

        sb.append("## Suggested Course of Action\n");
        if (report.suggestedCourseOfAction() == null || report.suggestedCourseOfAction().isEmpty()) {
            sb.append("_No actions suggested._\n");
        } else {
            int idx = 1;
            for (String action : report.suggestedCourseOfAction()) {
                sb.append(idx++).append(". ").append(action).append("\n");
            }
        }

        return sb.toString();
    }

    private String sanitizeFileSegment(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        return input.trim().toLowerCase().replaceAll("[^a-z0-9\\-_]", "-");
    }
}
