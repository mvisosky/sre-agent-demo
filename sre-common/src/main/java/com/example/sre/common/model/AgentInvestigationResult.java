package com.example.sre.common.model;

import java.util.List;

public record AgentInvestigationResult(
    List<IncidentReport.InvestigationStep> stepsTaken,
    String findings,
    String rootCauseHypothesis,
    List<String> suggestedCourseOfAction
) {
    public AgentInvestigationResult {
        if (stepsTaken == null) {
            stepsTaken = List.of();
        }
        if (suggestedCourseOfAction == null) {
            suggestedCourseOfAction = List.of();
        }
    }
}
