package com.example.rag_poc.store;

/** Outcome of indexing an uploaded file: the dataset id and whether it was created, replaced or unchanged. */
public record IndexResult(String datasetId, Status status) {

    public enum Status { CREATED, UPDATED, UNCHANGED }
}
