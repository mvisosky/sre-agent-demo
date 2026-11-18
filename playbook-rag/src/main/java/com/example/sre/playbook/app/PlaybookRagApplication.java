package com.example.sre.playbook.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

@SpringBootApplication(scanBasePackages = {
    "com.example.sre.playbook.service",
    "com.example.sre.playbook.controller",
    "com.example.sre.playbook.config",
    "com.example.sre.playbook.indexing"
})
public class PlaybookRagApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PlaybookRagApplication.class);
        app.setDefaultProperties(Map.of(
            "spring.application.name", "playbook-rag-service",
            "server.port", "8081",
            "spring.datasource.url", "jdbc:postgresql://localhost:5432/sre_agent_db",
            "spring.datasource.username", "sre_user",
            "spring.datasource.password", "sre_password",
            "spring.datasource.driver-class-name", "org.postgresql.Driver",
            "spring.ai.vectorstore.pgvector.initialize-schema", "true",
            "spring.ai.vectorstore.pgvector.index-type", "HNSW",
            "spring.ai.vectorstore.pgvector.distance-type", "COSINE_DISTANCE",
            "spring.ai.vectorstore.pgvector.dimensions", "384"
        ));
        app.run(args);
    }
}
