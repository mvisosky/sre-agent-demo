package com.example.sre.playbook.service;

import com.example.sre.common.model.PlaybookChunkResult;
import com.example.sre.common.model.PlaybookIngestRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybookRagServiceTest {

    @Test
    void shouldIngestPlaybookWithServiceMetadata() {
        InMemoryTestVectorStore vectorStore = new InMemoryTestVectorStore();
        PlaybookRagService ragService = new PlaybookRagService(vectorStore);

        PlaybookIngestRequest request = new PlaybookIngestRequest(
            "Payment-Service",
            "Payment Service Runbook",
            "1.2",
            "# Payment Runbook\nStep 1: Check HikariCP pool.\nStep 2: Check deployments."
        );

        int chunks = ragService.ingestPlaybook(request);
        assertThat(chunks).isGreaterThanOrEqualTo(1);
        assertThat(vectorStore.storedDocuments).isNotEmpty();
        assertThat(vectorStore.storedDocuments.getFirst().getMetadata())
            .containsEntry("serviceName", "payment-service")
            .containsEntry("title", "Payment Service Runbook")
            .containsEntry("version", "1.2");
    }

    @Test
    void shouldQueryPlaybooksFilteredByService() {
        InMemoryTestVectorStore vectorStore = new InMemoryTestVectorStore();
        vectorStore.storedDocuments.add(new Document(
            "doc-1",
            "Check HikariCP connection pool saturation.",
            Map.of("serviceName", "payment-service", "title", "Payment Runbook")
        ));

        PlaybookRagService ragService = new PlaybookRagService(vectorStore);

        List<PlaybookChunkResult> results = ragService.queryPlaybooksForService(
            "payment-service", "HTTP 500 timeout", 3);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().serviceName()).isEqualTo("payment-service");
        assertThat(results.getFirst().content()).contains("HikariCP");
    }

    static class InMemoryTestVectorStore implements VectorStore {
        final List<Document> storedDocuments = new ArrayList<>();

        @Override
        public void add(List<Document> documents) {
            storedDocuments.addAll(documents);
        }

        @Override
        public void delete(List<String> idList) {
            storedDocuments.removeIf(d -> idList.contains(d.getId()));
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.copyOf(storedDocuments);
        }
    }
}
