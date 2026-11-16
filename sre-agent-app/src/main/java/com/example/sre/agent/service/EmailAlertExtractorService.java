package com.example.sre.agent.service;

import com.example.sre.common.model.EmailWebhookRequest;
import com.example.sre.common.model.ExtractedAlertInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class EmailAlertExtractorService {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertExtractorService.class);

    private static final String EXTRACTION_SYSTEM_PROMPT = """
        You are an expert Site Reliability Engineering (SRE) alert parser.
        Extract structured alert metadata from the incoming alert email.
        
        Rules:
        1. Identify the primary affected microservice name in lowercase kebab-case (e.g., "payment-service", "order-service", "auth-service").
        2. Identify the deployment environment (e.g., "prod-us-east-1", "production", "staging").
        3. Identify the alert severity (e.g., "P1", "CRITICAL", "HIGH", "WARNING").
        4. Extract a concise errorSummary and a list of concrete technical symptoms mentioned in the email.
        5. List any affected endpoints, topics, databases, or resources mentioned.
        """;

    private final ChatClient chatClient;

    public EmailAlertExtractorService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public ExtractedAlertInfo extractAlertInfo(EmailWebhookRequest email) {
        log.info("Extracting alert info from email subject='{}', from='{}'", email.subject(), email.from());

        String userPrompt = """
            Alert Email Received:
            From: %s
            Subject: %s
            Received At: %s
            Body:
            %s
            """.formatted(email.from(), email.subject(), email.receivedAt(), email.body());

        ExtractedAlertInfo extracted = chatClient.prompt()
            .system(EXTRACTION_SYSTEM_PROMPT)
            .user(userPrompt)
            .call()
            .entity(ExtractedAlertInfo.class);

        log.info("Extracted serviceName='{}', severity='{}', symptoms={}",
            extracted.serviceName(), extracted.severity(), extracted.symptoms());
        return extracted;
    }
}
