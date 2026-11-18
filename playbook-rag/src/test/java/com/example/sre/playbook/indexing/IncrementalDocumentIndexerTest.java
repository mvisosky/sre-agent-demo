package com.example.sre.playbook.indexing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementalDocumentIndexerTest {

    static final String KEY = "serviceName";

    static class CountingStore implements VectorStore {
        final List<Document> docs = new ArrayList<>();
        int embedded;
        int addCalls;
        int deleteCalls;

        @Override public void add(List<Document> d) { addCalls++; embedded += d.size(); d.forEach(n -> { docs.removeIf(o -> o.getId().equals(n.getId())); docs.add(n); }); }
        @Override public void delete(List<String> ids) { deleteCalls++; docs.removeIf(x -> ids.contains(x.getId())); }
        @Override public void delete(Filter.Expression e) { }
        @Override public List<Document> similaritySearch(SearchRequest r) { return List.copyOf(docs); }
        void resetCounts() { embedded = 0; addCalls = 0; deleteCalls = 0; }
        long count(String svc) { return docs.stream().filter(d -> svc.equals(d.getMetadata().get(KEY))).count(); }
    }

    CountingStore store;
    IncrementalDocumentIndexer indexer;

    static String md(String s1, String s2, String s3, String s4) {
        return "# Title\n\n## One\n" + s1 + "\n\n## Two\n" + s2 + "\n\n## Three\n" + s3 + "\n\n## Four\n" + s4 + "\n";
    }

    @BeforeEach
    void setUp() {
        store = new CountingStore();
        indexer = new IncrementalDocumentIndexer(store, new InMemoryChunkHashRepository(), new MarkdownSectionSplitter());
    }

    @Test
    void initialIngestionAddsAllChunksWithHashAndIdentifier() {
        SyncResult r = indexer.syncDocument(KEY, "svc-a", md("a", "b", "c", "d"), Map.of());
        assertThat(r.added()).isEqualTo(5);
        assertThat(store.docs).allSatisfy(d -> assertThat(d.getMetadata())
            .containsEntry(KEY, "svc-a").containsKey("content_hash").containsKey("chunk_index"));
    }

    @Test
    void unchangedReingestIsNoOp() {
        String m = md("a", "b", "c", "d");
        indexer.syncDocument(KEY, "svc-a", m, Map.of());
        store.resetCounts();
        SyncResult r = indexer.syncDocument(KEY, "svc-a", m, Map.of());
        assertThat(r.isNoOp()).isTrue();
        assertThat(r.unchanged()).isEqualTo(5);
        assertThat(store.addCalls).isZero();
        assertThat(store.deleteCalls).isZero();
        assertThat(store.embedded).isZero();
    }

    @Test
    void editingFirstSectionOnlyTouchesThatSection() {
        indexer.syncDocument(KEY, "svc-a", md("a", "b", "c", "d"), Map.of());
        store.resetCounts();
        SyncResult r = indexer.syncDocument(KEY, "svc-a", md("a EDITED with more text\n\nnew para", "b", "c", "d"), Map.of());
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.deleted()).isEqualTo(1);
        assertThat(r.unchanged()).isEqualTo(4);
        assertThat(store.embedded).isEqualTo(1);
        assertThat(store.count("svc-a")).isEqualTo(5);
    }

    @Test
    void sectionDeletionAndAdditionDoNotAffectOtherDocuments() {
        indexer.syncDocument(KEY, "svc-a", md("a", "b", "c", "d"), Map.of());
        indexer.syncDocument(KEY, "svc-b", md("a", "b", "c", "d"), Map.of());
        SyncResult removed = indexer.syncDocument(KEY, "svc-a", "# Title\n\n## One\na\n\n## Four\nd\n", Map.of());
        assertThat(removed.deleted()).isEqualTo(2);
        assertThat(store.count("svc-a")).isEqualTo(3);
        assertThat(store.count("svc-b")).isEqualTo(5);
        SyncResult added = indexer.syncDocument(KEY, "svc-a", "# Title\n\n## One\na\n\n## Four\nd\n\n## Five\ne\n", Map.of());
        assertThat(added.added()).isEqualTo(1);
        assertThat(added.deleted()).isZero();
        assertThat(store.count("svc-b")).isEqualTo(5);
    }

    @Test
    void duplicateChunksCollapseAndEmptyFileDeletesAll() {
        SyncResult r = indexer.syncDocument(KEY, "svc-a", "## S\nsame\n\n---\n\n## S\nsame\n", Map.of());
        assertThat(r.total()).isEqualTo(1);
        SyncResult empty = indexer.syncDocument(KEY, "svc-a", "  \n", Map.of());
        assertThat(empty.deleted()).isEqualTo(1);
        assertThat(store.count("svc-a")).isZero();
    }

    @Test
    void reindexReplacesAllChunks() {
        String m = md("a", "b", "c", "d");
        indexer.syncDocument(KEY, "svc-a", m, Map.of());
        store.resetCounts();
        SyncResult r = indexer.reindexDocument(KEY, "svc-a", m, Map.of());
        assertThat(store.count("svc-a")).isEqualTo(5);
        assertThat(r.added()).isEqualTo(5);
        assertThat(r.deleted()).isZero(); // same deterministic ids: re-add upserts, nothing stale
    }

    @Test
    void splitterIgnoresHeadingsInCodeFences() {
        var sections = new MarkdownSectionSplitter().split("## A\n```\n# not heading\n```\n## B\nx");
        assertThat(sections).hasSize(2);
    }
}
