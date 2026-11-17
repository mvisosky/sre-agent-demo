package com.example.sre.playbook.service;

import com.example.sre.common.model.PlaybookChunkResult;
import com.example.sre.common.model.PlaybookIngestRequest;
import com.example.sre.common.security.PromptInjectionDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlaybookRagService {

    private static final Logger log = LoggerFactory.getLogger(PlaybookRagService.class);

    public static final String META_SERVICE_NAME = "serviceName";
    public static final String META_TITLE = "title";
    public static final String META_VERSION = "version";
    public static final String META_INGESTED_AT = "ingestedAt";

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;

    public PlaybookRagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.textSplitter = new TokenTextSplitter();
    }

    /**
     * Splits a playbook Markdown document into chunks, tags each chunk with service metadata,
     * and stores them in the PGVector store.
     */
    public int ingestPlaybook(PlaybookIngestRequest request) {
        // Enforced here (not only via @Valid on the controller) so PlaybookSeeder and other callers are covered too.
        // Playbook text is later fed to the LLM as retrieved context, so reject injection payloads at ingest.
        for (String field : new String[] {request.title(), request.version(), request.markdownContent()}) {
            PromptInjectionDetector.detect(field).ifPresent(rule -> {
                log.warn("SECURITY: playbook ingest rejected [rule={}]", rule);
                throw new IllegalArgumentException("Playbook content rejected: suspected prompt-injection content");
            });
        }
        String normalizedService = normalizeServiceName(request.serviceName());
        log.info("Ingesting playbook '{}' (version {}) for service '{}'",
            request.title(), request.version(), normalizedService);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(META_SERVICE_NAME, normalizedService);
        metadata.put(META_TITLE, request.title());
        metadata.put(META_VERSION, request.version());
        metadata.put(META_INGESTED_AT, Instant.now().toString());

        Document sourceDoc = new Document(request.markdownContent(), metadata);
        List<Document> chunks = textSplitter.apply(List.of(sourceDoc));

        vectorStore.add(chunks);
        log.info("Stored {} vector chunks for service '{}'", chunks.size(), normalizedService);
        return chunks.size();
    }

    /**
     * Queries PGVector for playbook instructions matching the given serviceName and symptom query.
     * Falls back to an unfiltered semantic search if no chunks match the exact serviceName filter.
     */
    public List<PlaybookChunkResult> queryPlaybooksForService(String serviceName, String symptomQuery, int topK) {
        String normalizedService = normalizeServiceName(serviceName);
        String effectiveQuery = (symptomQuery == null || symptomQuery.isBlank())
            ? "Incident investigation playbook and troubleshooting steps for " + normalizedService
            : symptomQuery;

        log.info("Querying playbook RAG for service='{}', query='{}', topK={}",
            normalizedService, effectiveQuery, topK);

        FilterExpressionBuilder feb = new FilterExpressionBuilder();
        SearchRequest filteredRequest = SearchRequest.builder()
            .query(effectiveQuery)
            .topK(topK)
            .filterExpression(feb.eq(META_SERVICE_NAME, normalizedService).build())
            .build();

        List<Document> docs = vectorStore.similaritySearch(filteredRequest);

        if (docs == null || docs.isEmpty()) {
            log.warn("No exact playbook chunks matched serviceName='{}'. Falling back to semantic search.", normalizedService);
            SearchRequest fallbackRequest = SearchRequest.builder()
                .query(normalizedService + " " + effectiveQuery)
                .topK(topK)
                .build();
            docs = vectorStore.similaritySearch(fallbackRequest);
        }

        if (docs == null) {
            return List.of();
        }

        return docs.stream()
            .map(doc -> new PlaybookChunkResult(
                doc.getId(),
                String.valueOf(doc.getMetadata().getOrDefault(META_SERVICE_NAME, normalizedService)),
                String.valueOf(doc.getMetadata().getOrDefault(META_TITLE, "Playbook")),
                doc.getText(),
                doc.getMetadata()
            ))
            .toList();
    }

    private String normalizeServiceName(String serviceName) {
        return serviceName == null ? "unknown-service" : serviceName.trim().toLowerCase();
    }
}
