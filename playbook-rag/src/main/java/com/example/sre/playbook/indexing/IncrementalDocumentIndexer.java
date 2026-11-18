package com.example.sre.playbook.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hash-diffs a Markdown document against the chunks stored for its identifier and applies only the delta:
 * unchanged chunks are untouched (no embedding, no writes), new/modified chunks are added, stale chunks deleted.
 * Adds happen before deletes so a failed embedding call never loses existing data.
 */
public class IncrementalDocumentIndexer {

    private static final Logger log = LoggerFactory.getLogger(IncrementalDocumentIndexer.class);

    private final VectorStore vectorStore;
    private final ChunkHashRepository repository;
    private final MarkdownSectionSplitter splitter;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public IncrementalDocumentIndexer(VectorStore vectorStore, ChunkHashRepository repository,
                                      MarkdownSectionSplitter splitter) {
        this.vectorStore = vectorStore;
        this.repository = repository;
        this.splitter = splitter;
    }

    public SyncResult syncDocument(String identifierKey, String identifierValue, Resource markdown) throws IOException {
        return syncDocument(identifierKey, identifierValue, markdown.getContentAsString(StandardCharsets.UTF_8), Map.of());
    }

    public SyncResult syncDocument(String identifierKey, String identifierValue, String markdown,
                                   Map<String, Object> extraMetadata) {
        return sync(identifierKey, identifierValue, markdown, extraMetadata, false);
    }

    /** Drops every existing chunk for the identifier and re-embeds the whole document. */
    public SyncResult reindexDocument(String identifierKey, String identifierValue, String markdown,
                                      Map<String, Object> extraMetadata) {
        return sync(identifierKey, identifierValue, markdown, extraMetadata, true);
    }

    private SyncResult sync(String key, String value, String markdown, Map<String, Object> extra, boolean force) {
        JdbcChunkHashRepository.requireSafe(key);
        Object lock = locks.computeIfAbsent(key + "\u0000" + value, k -> new Object());
        synchronized (lock) {
            // Identical chunks within a file collapse to one (keyed by hash).
            Map<String, Document> desired = new LinkedHashMap<>();
            int idx = 0;
            for (MarkdownSectionSplitter.Section s : splitter.split(markdown)) {
                String hash = sha256(s.text());
                if (desired.containsKey(hash)) {
                    continue;
                }
                Map<String, Object> md = new HashMap<>(extra);
                md.put(key, value);
                md.put(ChunkMetadata.CONTENT_HASH, hash);
                md.put(ChunkMetadata.CHUNK_INDEX, idx++);
                md.put(ChunkMetadata.SECTION, s.heading());
                // Deterministic id: re-adding an unchanged chunk is an upsert rather than a duplicate.
                String id = UUID.nameUUIDFromBytes((key + ":" + value + ":" + hash).getBytes(StandardCharsets.UTF_8)).toString();
                desired.put(hash, new Document(id, s.text(), md));
            }

            List<String> toDelete = new ArrayList<>();
            Set<String> kept = new HashSet<>();
            for (ExistingChunk e : repository.findByDocument(key, value)) {
                if (!force && e.contentHash() != null && desired.containsKey(e.contentHash()) && kept.add(e.contentHash())) {
                    continue;
                }
                toDelete.add(e.id());
            }
            List<Document> toAdd = new ArrayList<>();
            desired.forEach((hash, doc) -> {
                if (!kept.contains(hash)) {
                    toAdd.add(doc);
                }
            });
            // With force, rows are deleted after the add; ids are deterministic so re-adds upsert rather than clash.
            toDelete.removeAll(toAdd.stream().map(Document::getId).toList());

            if (!toAdd.isEmpty()) {
                vectorStore.add(toAdd);
                repository.chunksAdded(key, value, toAdd);
            }
            if (!toDelete.isEmpty()) {
                vectorStore.delete(toDelete);
                repository.chunksDeleted(toDelete);
            }
            SyncResult result = new SyncResult(toAdd.size(), kept.size(), toDelete.size());
            log.info("Synced {}='{}': added={}, unchanged={}, deleted={}", key, value,
                result.added(), result.unchanged(), result.deleted());
            return result;
        }
    }

    static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
