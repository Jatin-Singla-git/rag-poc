package com.example.rag_poc.store;

import java.util.List;

/** Rows whose categorical cells match words in the question. */
public record ScopedRows(List<String> rows, int totalMatches, String scope) {
}
