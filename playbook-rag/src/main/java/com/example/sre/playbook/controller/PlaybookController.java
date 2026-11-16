package com.example.sre.playbook.controller;

import com.example.sre.common.model.PlaybookChunkResult;
import com.example.sre.common.model.PlaybookIngestRequest;
import com.example.sre.playbook.service.PlaybookRagService;
import com.example.sre.playbook.service.PlaybookSeeder;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/playbooks")
public class PlaybookController {

    private final PlaybookRagService playbookRagService;
    private final PlaybookSeeder playbookSeeder;

    public PlaybookController(PlaybookRagService playbookRagService, PlaybookSeeder playbookSeeder) {
        this.playbookRagService = playbookRagService;
        this.playbookSeeder = playbookSeeder;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingestPlaybook(@Valid @RequestBody PlaybookIngestRequest request) {
        int chunksStored = playbookRagService.ingestPlaybook(request);
        return ResponseEntity.ok(Map.of(
            "serviceName", request.serviceName(),
            "title", request.title(),
            "version", request.version(),
            "chunksStored", chunksStored,
            "status", "INGESTED"
        ));
    }

    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seedDefaultPlaybooks(
            @RequestParam(defaultValue = "false") boolean force) {
        int seededCount = playbookSeeder.seedDefaultPlaybooks(force);
        return ResponseEntity.ok(Map.of(
            "playbooksSeeded", seededCount,
            "forceReingest", force
        ));
    }

    @GetMapping("/search")
    public ResponseEntity<List<PlaybookChunkResult>> searchPlaybooks(
            @RequestParam String serviceName,
            @RequestParam(defaultValue = "incident troubleshooting steps") String query,
            @RequestParam(defaultValue = "4") int topK) {
        return ResponseEntity.ok(playbookRagService.queryPlaybooksForService(serviceName, query, topK));
    }
}
