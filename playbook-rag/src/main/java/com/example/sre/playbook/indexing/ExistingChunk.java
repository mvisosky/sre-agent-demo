package com.example.sre.playbook.indexing;

/** A chunk row already stored for a document: its vector-store id and the stored {@code content_hash} (may be null for legacy rows). */
public record ExistingChunk(String id, String contentHash) {
}
