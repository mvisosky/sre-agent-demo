package com.example.sre.playbook.service;

import com.example.sre.common.model.PlaybookChunkResult;
import com.example.sre.common.model.PlaybookIngestRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class PlaybookSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlaybookSeeder.class);

    private final PlaybookRagService playbookRagService;
    private final boolean seedOnStartup;

    public PlaybookSeeder(
            PlaybookRagService playbookRagService,
            @Value("${sre.playbook.seed-on-startup:true}") boolean seedOnStartup) {
        this.playbookRagService = playbookRagService;
        this.seedOnStartup = seedOnStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedOnStartup) {
            log.info("Playbook startup seeding is disabled (sre.playbook.seed-on-startup=false).");
            return;
        }
        try {
            int seeded = seedDefaultPlaybooks(false);
            log.info("Playbook startup seeding finished ({} new playbooks ingested).", seeded);
        } catch (Exception e) {
            log.warn("Skipping automatic playbook seeding on startup (verify DB and GOOGLE_API_KEY): {}",
                e.getMessage());
        }
    }

    public int seedDefaultPlaybooks(boolean forceReingest) {
        int count = 0;
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:playbooks/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                String serviceName = filename.replace(".md", "").trim().toLowerCase();
                if (!forceReingest) {
                    List<PlaybookChunkResult> existing = playbookRagService.queryPlaybooksForService(
                        serviceName, "troubleshooting playbook", 1);
                    boolean hasExactMatch = existing.stream()
                        .anyMatch(c -> serviceName.equalsIgnoreCase(c.serviceName()));
                    if (hasExactMatch) {
                        log.info("Playbook for service '{}' already exists in PGVector, skipping seed.", serviceName);
                        continue;
                    }
                }

                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                String title = extractTitle(content, serviceName + " Runbook");
                playbookRagService.ingestPlaybook(new PlaybookIngestRequest(
                    serviceName,
                    title,
                    "1.0",
                    content
                ));
                count++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to seed built-in playbooks: " + e.getMessage(), e);
        }
        return count;
    }

    private String extractTitle(String markdown, String fallback) {
        List<String> lines = new ArrayList<>(markdown.lines().toList());
        for (String line : lines) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return fallback;
    }
}
