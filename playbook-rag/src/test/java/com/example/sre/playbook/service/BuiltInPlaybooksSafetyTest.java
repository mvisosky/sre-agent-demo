package com.example.sre.playbook.service;

import com.example.sre.common.security.PromptInjectionDetector;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInPlaybooksSafetyTest {

    @Test
    void builtInPlaybooksPassIngestValidation() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:playbooks/*.md");
        assertThat(resources).isNotEmpty();
        for (Resource r : resources) {
            assertThat(PromptInjectionDetector.detect(r.getContentAsString(StandardCharsets.UTF_8)))
                .as(r.getFilename()).isEmpty();
        }
    }
}
