package com.example.sre.common.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record IncidentReport(
    String incidentId,
    Instant timestamp,
    ExtractedAlertInfo alertInfo,
    List<String> matchedPlaybooks,
    List<InvestigationStep> stepsTaken,
    String findings,
    String rootCauseHypothesis,
    List<String> suggestedCourseOfAction,
    String markdownReportPath,
    String jsonReportPath,
    boolean deduplicated,
    String primaryIncidentId,
    int occurrenceCount,
    Instant lastSeenAt,
    List<CorrelatedAlert> correlatedAlerts
) {
    public IncidentReport {
        if (primaryIncidentId == null || primaryIncidentId.isBlank()) {
            primaryIncidentId = incidentId;
        }
        if (occurrenceCount <= 0) {
            occurrenceCount = 1;
        }
        if (lastSeenAt == null) {
            lastSeenAt = timestamp;
        }
        correlatedAlerts = (correlatedAlerts == null) ? List.of() : List.copyOf(correlatedAlerts);
    }

    public IncidentReport(
        String incidentId,
        Instant timestamp,
        ExtractedAlertInfo alertInfo,
        List<String> matchedPlaybooks,
        List<InvestigationStep> stepsTaken,
        String findings,
        String rootCauseHypothesis,
        List<String> suggestedCourseOfAction,
        String markdownReportPath,
        String jsonReportPath
    ) {
        this(
            incidentId,
            timestamp,
            alertInfo,
            matchedPlaybooks,
            stepsTaken,
            findings,
            rootCauseHypothesis,
            suggestedCourseOfAction,
            markdownReportPath,
            jsonReportPath,
            false,
            incidentId,
            1,
            timestamp,
            List.of()
        );
    }

    public IncidentReport withFilePaths(String newMarkdownPath, String newJsonPath) {
        return new IncidentReport(
            incidentId,
            timestamp,
            alertInfo,
            matchedPlaybooks,
            stepsTaken,
            findings,
            rootCauseHypothesis,
            suggestedCourseOfAction,
            newMarkdownPath,
            newJsonPath,
            deduplicated,
            primaryIncidentId,
            occurrenceCount,
            lastSeenAt,
            correlatedAlerts
        );
    }

    public IncidentReport withCorrelatedOccurrence(CorrelatedAlert alert, Instant seenAt) {
        List<CorrelatedAlert> updated = new ArrayList<>(correlatedAlerts);
        if (alert != null) {
            updated.add(alert);
        }
        return new IncidentReport(
            incidentId,
            timestamp,
            alertInfo,
            matchedPlaybooks,
            stepsTaken,
            findings,
            rootCauseHypothesis,
            suggestedCourseOfAction,
            markdownReportPath,
            jsonReportPath,
            true,
            primaryIncidentId,
            occurrenceCount + 1,
            seenAt != null ? seenAt : Instant.now(),
            updated
        );
    }

    public record InvestigationStep(
        int stepNumber,
        String action,
        String toolUsed,
        String observation
    ) {}

    public record CorrelatedAlert(
        String subject,
        String from,
        Instant receivedAt,
        String matchedStage
    ) {}
}
