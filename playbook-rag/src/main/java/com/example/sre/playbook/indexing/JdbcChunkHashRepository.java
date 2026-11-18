package com.example.sre.playbook.indexing;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.regex.Pattern;

/** Reads {@code id} and {@code metadata->>'content_hash'} straight from the pgvector table. */
public class JdbcChunkHashRepository implements ChunkHashRepository {

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;

    public JdbcChunkHashRepository(JdbcTemplate jdbcTemplate, String tableName) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = requireSafe(tableName);
    }

    @Override
    public List<ExistingChunk> findByDocument(String identifierKey, String identifierValue) {
        // The key is inlined (validated) rather than bound so the expression matches the B-tree expression index.
        String sql = "SELECT id::text, metadata->>'" + ChunkMetadata.CONTENT_HASH + "' FROM " + tableName
            + " WHERE metadata->>'" + requireSafe(identifierKey) + "' = ?";
        return jdbcTemplate.query(sql, (rs, i) -> new ExistingChunk(rs.getString(1), rs.getString(2)), identifierValue);
    }

    static String requireSafe(String ident) {
        if (ident == null || !SAFE_IDENT.matcher(ident).matches()) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + ident);
        }
        return ident;
    }
}
