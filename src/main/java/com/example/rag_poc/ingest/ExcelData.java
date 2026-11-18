package com.example.rag_poc.ingest;

import java.util.List;
import java.util.Map;

/**
 * Parsed contents of one uploaded Excel sheet (kept in memory only).
 */
public record ExcelData(
        String filename,
        String sheetName,
        List<String> headers,
        List<Map<String, String>> records
) {
}
