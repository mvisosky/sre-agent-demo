package com.example.sre.playbook.indexing;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class IndexingConfig {

    @Bean
    public ChunkHashRepository chunkHashRepository(VectorStore vectorStore, ObjectProvider<JdbcTemplate> jdbc,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String table) {
        JdbcTemplate jt = jdbc.getIfAvailable();
        if (vectorStore instanceof PgVectorStore && jt != null) {
            return new JdbcChunkHashRepository(jt, table);
        }
        return new InMemoryChunkHashRepository();
    }

    @Bean
    public IncrementalDocumentIndexer incrementalDocumentIndexer(VectorStore vectorStore, ChunkHashRepository repo) {
        return new IncrementalDocumentIndexer(vectorStore, repo, new MarkdownSectionSplitter());
    }

    @Bean
    public VectorStoreIndexInitializer vectorStoreIndexInitializer(VectorStore vectorStore,
            ObjectProvider<JdbcTemplate> jdbc,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String table,
            @Value("${sre.playbook.document-key:serviceName}") String key) {
        return new VectorStoreIndexInitializer(vectorStore instanceof PgVectorStore ? jdbc.getIfAvailable() : null, table, key);
    }
}
