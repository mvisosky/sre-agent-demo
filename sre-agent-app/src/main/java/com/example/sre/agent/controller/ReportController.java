package com.example.sre.agent.controller;

import com.example.sre.agent.service.LocalFileReportPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final LocalFileReportPublisher reportPublisher;

    public ReportController(LocalFileReportPublisher reportPublisher) {
        this.reportPublisher = reportPublisher;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listReports() {
        return ResponseEntity.ok(Map.of(
            "reportsDirectory", reportPublisher.getReportsDir().toString(),
            "files", reportPublisher.listReportFiles()
        ));
    }

    @GetMapping("/{filename}")
    public ResponseEntity<String> getReportContent(@PathVariable String filename) {
        String content = reportPublisher.readReportFile(filename);
        MediaType mediaType = filename.endsWith(".json")
            ? MediaType.APPLICATION_JSON
            : MediaType.TEXT_MARKDOWN;
        return ResponseEntity.ok()
            .contentType(mediaType)
            .body(content);
    }
}
