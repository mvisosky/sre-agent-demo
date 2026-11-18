package com.example.sre.playbook.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates the B-tree expression index on the per-document identifier in the metadata column.
 * Runs after all singletons exist, i.e. after PgVectorStore has initialized the table schema.
 */
public class VectorStoreIndexInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreIndexInitializer.class);

    private final JdbcTemplate jdbc;
    private final String table;
    private final String key;

    public VectorStoreIndexInitializer(JdbcTemplate jdbc, String table, String key) {
        this.jdbc = jdbc;
        this.table = JdbcChunkHashRepository.requireSafe(table);
        this.key = JdbcChunkHashRepository.requireSafe(key);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_doc_identifier ON " + table
                + " ((metadata->>'" + key + "'))");
            log.info("Ensured expression index idx_{}_doc_identifier on {}.metadata->>'{}'", table, table, key);
        } catch (Exception e) {
            log.warn("Could not create document identifier index: {}", e.getMessage());
        }
    }
}
