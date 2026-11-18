package com.example.rag_poc.store;

/** One vector-search hit from a search across all datasets. */
public record DatasetHit(String datasetId, double score, String text) {
}
