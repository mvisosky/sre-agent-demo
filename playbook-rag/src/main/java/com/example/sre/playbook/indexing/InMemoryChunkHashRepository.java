package com.example.sre.playbook.indexing;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks chunks in memory for vector stores that cannot be queried by metadata (e.g. SimpleVectorStore, local profile). */
public class InMemoryChunkHashRepository implements ChunkHashRepository {

    private final Map<String, Map<String, String>> byDocument = new ConcurrentHashMap<>();

    private static String key(String k, String v) {
        return k + "\u0000" + v;
    }

    @Override
    public List<ExistingChunk> findByDocument(String identifierKey, String identifierValue) {
        List<ExistingChunk> out = new ArrayList<>();
        byDocument.getOrDefault(key(identifierKey, identifierValue), Map.of())
            .forEach((id, hash) -> out.add(new ExistingChunk(id, hash)));
        return out;
    }

    @Override
    public void chunksAdded(String identifierKey, String identifierValue, List<Document> added) {
        Map<String, String> m = byDocument.computeIfAbsent(key(identifierKey, identifierValue), k -> new ConcurrentHashMap<>());
        for (Document d : added) {
            m.put(d.getId(), String.valueOf(d.getMetadata().get(ChunkMetadata.CONTENT_HASH)));
        }
    }

    @Override
    public void chunksDeleted(List<String> ids) {
        byDocument.values().forEach(m -> ids.forEach(m::remove));
    }
}
