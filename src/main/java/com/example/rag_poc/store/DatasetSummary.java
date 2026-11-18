package com.example.rag_poc.store;

/** Summary of one stored dataset (cheap to compute; no content). */
public record DatasetSummary(String datasetId, String filename, String type, int parts, long uploadedAt) {
}
