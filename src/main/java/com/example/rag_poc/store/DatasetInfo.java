package com.example.rag_poc.store;

import java.util.List;
import java.util.Map;

/** Info about one stored dataset. */
public record DatasetInfo(String datasetId, String filename, String sheetName,
                          List<String> columns, Map<String, String> sampleRow, String type) {
}
