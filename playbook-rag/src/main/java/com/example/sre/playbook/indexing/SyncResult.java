package com.example.sre.playbook.indexing;

/** Outcome of an incremental sync: how many chunks were embedded/added, left untouched, or deleted. */
public record SyncResult(int added, int unchanged, int deleted) {

    /** Number of chunks that now exist for the document. */
    public int total() {
        return added + unchanged;
    }

    public boolean isNoOp() {
        return added == 0 && deleted == 0;
    }
}
