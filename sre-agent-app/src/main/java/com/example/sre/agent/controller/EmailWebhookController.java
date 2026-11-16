package com.example.sre.agent.controller;

import com.example.sre.agent.service.AlertFloodControlService;
import com.example.sre.agent.service.AlertFloodControlService.DeduplicationStats;
import com.example.sre.agent.service.SreInvestigationAgentService;
import com.example.sre.common.model.EmailWebhookRequest;
import com.example.sre.common.model.IncidentReport;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks")
public class EmailWebhookController {

    private final SreInvestigationAgentService investigationAgentService;
    private final AlertFloodControlService floodControlService;

    public EmailWebhookController(
            SreInvestigationAgentService investigationAgentService,
            AlertFloodControlService floodControlService) {
        this.investigationAgentService = investigationAgentService;
        this.floodControlService = floodControlService;
    }

    @PostMapping("/email")
    public ResponseEntity<IncidentReport> receiveAlertEmail(@Valid @RequestBody EmailWebhookRequest request) {
        IncidentReport report = investigationAgentService.handleAlertEmail(request);
        return ResponseEntity.ok()
            .header("X-SRE-Alert-Deduplicated", String.valueOf(report.deduplicated()))
            .header("X-SRE-Primary-Incident-Id", report.primaryIncidentId())
            .header("X-SRE-Alert-Occurrences", String.valueOf(report.occurrenceCount()))
            .body(report);
    }

    @GetMapping("/deduplication/stats")
    public ResponseEntity<DeduplicationStats> getDeduplicationStats() {
        return ResponseEntity.ok(floodControlService.getStats());
    }

    @DeleteMapping("/deduplication/cache")
    public ResponseEntity<Map<String, String>> clearDeduplicationCache() {
        floodControlService.clearAll();
        return ResponseEntity.ok(Map.of(
            "status", "CLEARED",
            "message", "Active alert deduplication windows and flood counters have been reset."
        ));
    }
}
