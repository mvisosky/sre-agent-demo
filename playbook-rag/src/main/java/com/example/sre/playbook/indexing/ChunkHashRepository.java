package com.example.sre.playbook.indexing;

import org.springframework.ai.document.Document;

import java.util.List;

/** Looks up the chunks currently stored for a document identifier (delta calculation input). */
public interface ChunkHashRepository {

    List<ExistingChunk> findByDocument(String identifierKey, String identifierValue);

    /** Hook for repositories that cannot query the vector store and must track state themselves. */
    default void chunksAdded(String identifierKey, String identifierValue, List<Document> added) {
    }

    /** See {@link #chunksAdded}. */
    default void chunksDeleted(List<String> ids) {
    }
}
