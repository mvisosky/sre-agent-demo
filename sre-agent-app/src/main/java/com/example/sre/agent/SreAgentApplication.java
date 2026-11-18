package com.example.sre.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "com.example.sre.agent",
    "com.example.sre.playbook.service",
    "com.example.sre.playbook.controller",
    "com.example.sre.playbook.config",
    "com.example.sre.playbook.indexing",
    "com.example.sre.tools"
})
public class SreAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SreAgentApplication.class, args);
    }
}
