package com.example.rag_poc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Stores Excel rows in PostgreSQL (pgvector) and reads them back.
 * <p>
 * ONE table holds everything. Each Excel row is one vector-store document:
 * <ul>
 *   <li>content   = readable text of the row (this is what gets embedded)</li>
 *   <li>metadata  = datasetId, filename, sheetName, rowNumber, uploadedAt, columns (ordered
 *                   header list) and "row" (every column -> original cell text)</li>
 * </ul>
 * Vector search uses the embedding; exact calculations use SQL over metadata.row.
 * <p>
 * Dataset rule: datasetId is derived from the filename. Uploading a file with the same
 * name replaces the earlier rows of that dataset; different filenames live side by side.
 * Questions use the most recently uploaded dataset unless a filename is given.
 */
@Service
public class ExcelVectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(ExcelVectorStoreService.class);

    /** Info about one stored dataset. */
    public record DatasetInfo(String datasetId, String filename, String sheetName,
                              List<String> columns, Map<String, String> sampleRow, String type) {
    }

    private static final String JSON = "metadata::jsonb";
    // Parses a cell to a number in three steps:
    //  1. "(₹64,880)" (value wrapped in parentheses) is rewritten to "-₹64,880", i.e. negative;
    //  2. everything except digits, '.' and '-' is stripped (currency symbols, commas, spaces);
    //  3. only values that then look like a number are cast. "-", text or blanks become NULL
    //     (ignored by SUM/AVG/MIN/MAX) instead of making the whole query fail.
    private static final String NUMBER_EXPR =
            "CAST(substring(regexp_replace("
                    + "regexp_replace(" + JSON + "->'row'->>?, '^\\s*\\(([^)]*)\\)\\s*$', '-\\1'),"
                    + " '[^0-9.-]', '', 'g')"
                    + " from '^-?[0-9]+(?:[.][0-9]+)?$') AS numeric)";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final String table;
    private final int topK;
    private final double similarityThreshold;

    public ExcelVectorStoreService(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String table,
            @Value("${app.rag.top-k:8}") int topK,
            @Value("${app.rag.similarity-threshold:0.2}") double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.table = table;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    // ------------------------------------------------------------------ ingest

    /** Replaces the dataset for this filename with the given rows. Returns the datasetId. */
    @Transactional
    public String ingest(ExcelData data) {
        String datasetId = toDatasetId(data.filename());
        long uploadedAt = System.currentTimeMillis();

        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < data.records().size(); i++) {
            Map<String, String> row = data.records().get(i);
            int rowNumber = i + 2; // Excel row number (row 1 is the header)

            StringJoiner text = new StringJoiner("; ");
            row.forEach((column, value) -> {
                if (!value.isEmpty()) {
                    text.add(column + ": " + value);
                }
            });

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("datasetId", datasetId);
            metadata.put("filename", data.filename());
            metadata.put("sheetName", data.sheetName());
            metadata.put("rowNumber", rowNumber);
            metadata.put("uploadedAt", uploadedAt);
            metadata.put("columns", data.headers());
            metadata.put("row", row);

            // Deterministic id: same dataset + same row number always maps to the same record.
            String id = UUID.nameUUIDFromBytes(
                    (datasetId + "#" + rowNumber).getBytes(StandardCharsets.UTF_8)).toString();

            documents.add(Document.builder()
                    .id(id)
                    .text("Row " + rowNumber + " of " + data.filename() + " - " + text)
                    .metadata(metadata)
                    .build());
        }

        try {
            jdbcTemplate.update(
                    "DELETE FROM " + table + " WHERE " + JSON + "->>'datasetId' = ?", datasetId);
            vectorStore.add(documents); // embeds locally, then inserts into PostgreSQL
        } catch (Exception exception) {
            log.error("Failed to index dataset '{}' into pgvector", datasetId, exception);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not store the Excel data in the vector database.");
        }
        return datasetId;
    }

    // ------------------------------------------------- cross-dataset candidates

    /** Vector search over ALL datasets (no dataset filter), keeping scores for ranking. */
    public List<DatasetHit> searchAllDatasets(String question, int limit) {
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(limit).build());
        List<DatasetHit> hits = new ArrayList<>();
        for (Document doc : results) {
            Object id = doc.getMetadata().get("datasetId");
            if (id != null) {
                hits.add(new DatasetHit(id.toString(),
                        doc.getScore() == null ? 0.0 : doc.getScore(), doc.getText()));
            }
        }
        return hits;
    }

    /** Datasets having a cell that exactly equals an identifier-like word of the question. */
    public Set<String> findDatasetIdsByIdentifier(String question) {
        Set<String> tokens = new LinkedHashSet<>();
        var matcher = IDENTIFIER.matcher(question);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 4 && token.matches(".*[A-Za-z].*") && token.matches(".*\\d.*")) {
                tokens.add(token.toLowerCase(Locale.ROOT));
            }
        }
        if (tokens.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", Collections.nCopies(tokens.size(), "?"));
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT t." + JSON + "->>'datasetId' FROM " + table + " t"
                        + " CROSS JOIN LATERAL jsonb_each_text(t." + JSON + "->'row') AS e"
                        + " WHERE lower(e.value) IN (" + placeholders + ")",
                String.class, tokens.toArray()));
    }

    // ------------------------------------------------------------------ PDF

    /** Embeds this many chunks per vector-store call, keeping each embedding request small. */
    private static final int PDF_BATCH_SIZE = 20;

    /**
     * Replaces the dataset for this PDF filename with its chunks (same table as Excel rows).
     * Each chunk is one document: content = chunk text, metadata = datasetId, filename, docType=pdf,
     * page, chunkIndex, totalPages, uploadedAt. Returns the datasetId.
     */
    @Transactional
    public String ingestPdf(PdfDocumentService.PdfData data) {
        String datasetId = toDatasetId(data.filename());
        long uploadedAt = System.currentTimeMillis();

        List<Document> documents = new ArrayList<>();
        for (PdfDocumentService.PdfChunk chunk : data.chunks()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("datasetId", datasetId);
            metadata.put("filename", data.filename());
            metadata.put("docType", "pdf");
            metadata.put("page", chunk.page());
            metadata.put("chunkIndex", chunk.chunkIndex());
            metadata.put("totalPages", data.totalPages());
            metadata.put("uploadedAt", uploadedAt);

            String id = UUID.nameUUIDFromBytes(
                    (datasetId + "#p" + chunk.page() + "#c" + chunk.chunkIndex())
                            .getBytes(StandardCharsets.UTF_8)).toString();
            documents.add(Document.builder().id(id).text(chunk.text()).metadata(metadata).build());
        }

        try {
            jdbcTemplate.update(
                    "DELETE FROM " + table + " WHERE " + JSON + "->>'datasetId' = ?", datasetId);
            for (int from = 0; from < documents.size(); from += PDF_BATCH_SIZE) {
                vectorStore.add(documents.subList(from, Math.min(from + PDF_BATCH_SIZE, documents.size())));
            }
        } catch (Exception exception) {
            log.error("Failed to index PDF dataset '{}' into pgvector", datasetId, exception);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not store the PDF in the vector database.");
        }
        return datasetId;
    }

    /** Vector similarity search restricted to one dataset; returns whole documents (text + page). */
    public List<Document> searchChunks(String datasetId, String question) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(topK)
                        .similarityThreshold(similarityThreshold)
                        .filterExpression(new FilterExpressionBuilder().eq("datasetId", datasetId).build())
                        .build());
    }

    // ------------------------------------------------------------------ lookup

    /** Summary of one stored dataset (cheap to compute; no content). */
    public record DatasetSummary(String datasetId, String filename, String type, int parts, long uploadedAt) {
    }

    /** One vector-search hit from a search across all datasets. */
    public record DatasetHit(String datasetId, double score, String text) {
    }

    /** Lists every stored dataset (Excel and PDF), most recently uploaded first. */
    public List<DatasetSummary> listDatasets() {
        return jdbcTemplate.query(
                "SELECT d->>'datasetId' AS id, min(d->>'filename') AS filename,"
                        + " min(COALESCE(d->>'docType', 'excel')) AS type, count(*) AS parts,"
                        + " max((d->>'uploadedAt')::bigint) AS uploaded"
                        + " FROM (SELECT " + JSON + " AS d FROM " + table + ") x"
                        + " GROUP BY 1 ORDER BY uploaded DESC",
                (rs, i) -> new DatasetSummary(rs.getString("id"), rs.getString("filename"),
                        rs.getString("type"), rs.getInt("parts"), rs.getLong("uploaded")));
    }

    /**
     * Resolves an explicitly requested filename (manual override) to a stored dataset.
     * 404 if no uploaded file has that name, 409 if nothing has been uploaded at all.
     */
    public DatasetInfo findDataset(String filename) {
        List<DatasetSummary> all = listDatasets();
        if (all.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No file has been uploaded. Upload one via POST /api/documents/upload first.");
        }
        String wanted = toDatasetId(filename.trim());
        if (all.stream().noneMatch(d -> d.datasetId().equals(wanted))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No uploaded file named '" + filename.trim() + "'. Uploaded files: "
                            + String.join(", ", all.stream().map(DatasetSummary::filename).toList()));
        }
        return loadDataset(wanted);
    }

    /** Loads filename, sheet, columns, a sample row and type of an existing dataset. */
    public DatasetInfo loadDataset(String datasetId) {
        String filenameStored = jdbcTemplate.queryForObject(
                "SELECT " + JSON + "->>'filename' FROM " + table
                        + " WHERE " + JSON + "->>'datasetId' = ? LIMIT 1",
                String.class, datasetId);
        String sheetName = jdbcTemplate.queryForObject(
                "SELECT " + JSON + "->>'sheetName' FROM " + table
                        + " WHERE " + JSON + "->>'datasetId' = ? LIMIT 1",
                String.class, datasetId);

        List<String> columns = jdbcTemplate.queryForList(
                "SELECT c.value FROM jsonb_array_elements_text((SELECT " + JSON + "->'columns' FROM "
                        + table + " WHERE " + JSON + "->>'datasetId' = ? LIMIT 1))"
                        + " WITH ORDINALITY AS c(value, ord) ORDER BY c.ord",
                String.class, datasetId);

        Map<String, String> rawSample = new HashMap<>();
        jdbcTemplate.query(
                "SELECT e.key, e.value FROM jsonb_each_text((SELECT " + JSON + "->'row' FROM " + table
                        + " WHERE " + JSON + "->>'datasetId' = ?"
                        + " ORDER BY (" + JSON + "->>'rowNumber')::int LIMIT 1)) AS e(key, value)",
                rs -> {
                    rawSample.put(rs.getString(1), rs.getString(2));
                },
                datasetId);
        Map<String, String> sample = new LinkedHashMap<>();
        for (String column : columns) {
            sample.put(column, rawSample.getOrDefault(column, ""));
        }

        // Rows ingested before PDF support have no docType: they are Excel datasets.
        String type = jdbcTemplate.queryForObject(
                "SELECT COALESCE(" + JSON + "->>'docType', 'excel') FROM " + table
                        + " WHERE " + JSON + "->>'datasetId' = ? LIMIT 1",
                String.class, datasetId);

        return new DatasetInfo(datasetId, filenameStored, sheetName, columns, sample, type);
    }

    // ------------------------------------------------------------------ search

    /** Vector similarity search restricted to one dataset. Returns the row texts. */
    public List<String> search(String datasetId, String question) {
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(topK)
                        .similarityThreshold(similarityThreshold)
                        .filterExpression(new FilterExpressionBuilder().eq("datasetId", datasetId).build())
                        .build());
        return results.stream().map(Document::getText).toList();
    }

    // ------------------------------------------------------------ exact lookup

    private static final java.util.regex.Pattern IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    /**
     * Exact identifier lookup (e.g. an Order ID like ORD-00004). Any word in the question that
     * contains both a letter and a digit is compared, case-insensitively, with the FULL value of
     * every cell. Returns the full row texts of matching rows (max 5), or an empty list.
     */
    public List<String> findRowsByIdentifier(String datasetId, String question) {
        Set<String> candidates = new LinkedHashSet<>();
        var matcher = IDENTIFIER.matcher(question);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 4 && token.matches(".*[A-Za-z].*") && token.matches(".*\\d.*")) {
                candidates.add(token);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            result.addAll(jdbcTemplate.queryForList(
                    "SELECT content FROM " + table + " WHERE " + JSON + "->>'datasetId' = ?"
                            + " AND EXISTS (SELECT 1 FROM jsonb_each_text(" + JSON + "->'row') AS e"
                            + " WHERE lower(e.value) = lower(?))"
                            + " ORDER BY (" + JSON + "->>'rowNumber')::int LIMIT 5",
                    String.class, datasetId, candidate));
        }
        return result.stream().distinct().limit(5).toList();
    }

    // ------------------------------------------------------ value-scoped lookup

    /** Rows whose categorical cells match words in the question. */
    public record ScopedRows(List<String> rows, int totalMatches, String scope) {
    }

    /** A column only counts as categorical if it has at most this many distinct values. */
    private static final int MAX_CATEGORY_VALUES = 20;
    private static final int MAX_SCOPED_ROWS = 20;

    /**
     * Finds categorical values mentioned in the question (e.g. "West" in column Region) and returns
     * the rows having them, using SQL on the whole dataset instead of similarity.
     * <ul>
     *   <li>Candidates are 1-3 word phrases of the question; phrases without a letter (plain
     *       numbers, years) are ignored.</li>
     *   <li>A phrase counts only if it equals a FULL cell value of a column with few distinct
     *       values (so unique columns such as IDs are never used here).</li>
     *   <li>Different columns are combined with AND, several values of one column with OR.</li>
     *   <li>At most 20 rows are returned (in sheet order); totalMatches says how many matched.</li>
     * </ul>
     * Returns null when nothing matches, so the caller can fall back to vector search.
     */
    public ScopedRows findRowsByCategoricalValues(String datasetId, String question) {
        String[] words = question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+");
        Set<String> phrases = new LinkedHashSet<>();
        for (int start = 0; start < words.length; start++) {
            StringBuilder phrase = new StringBuilder();
            for (int length = 0; length < 3 && start + length < words.length; length++) {
                if (words[start + length].isEmpty()) {
                    break;
                }
                if (length > 0) {
                    phrase.append(' ');
                }
                phrase.append(words[start + length]);
                if (phrase.toString().matches(".*\\p{L}.*")) {
                    phrases.add(phrase.toString());
                }
            }
        }
        if (phrases.isEmpty()) {
            return null;
        }

        String placeholders = String.join(",", Collections.nCopies(phrases.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(datasetId);
        params.addAll(phrases);
        List<Map<String, Object>> found = jdbcTemplate.queryForList(
                "SELECT e.key AS col, min(e.value) AS val FROM " + table + " t"
                        + " CROSS JOIN LATERAL jsonb_each_text(t." + JSON + "->'row') AS e"
                        + " WHERE t." + JSON + "->>'datasetId' = ? AND lower(e.value) IN (" + placeholders + ")"
                        + " GROUP BY e.key, lower(e.value)",
                params.toArray());

        // column -> matched values, keeping only categorical columns
        Map<String, List<String>> byColumn = new LinkedHashMap<>();
        for (Map<String, Object> match : found) {
            String column = (String) match.get("col");
            Long distinct = jdbcTemplate.queryForObject(
                    "SELECT count(DISTINCT " + JSON + "->'row'->>?) FROM " + table
                            + " WHERE " + JSON + "->>'datasetId' = ?",
                    Long.class, column, datasetId);
            if (distinct != null && distinct <= MAX_CATEGORY_VALUES) {
                byColumn.computeIfAbsent(column, k -> new ArrayList<>()).add((String) match.get("val"));
            }
        }
        if (byColumn.isEmpty()) {
            return null;
        }

        StringBuilder where = new StringBuilder(" WHERE " + JSON + "->>'datasetId' = ?");
        List<Object> whereParams = new ArrayList<>();
        whereParams.add(datasetId);
        List<String> scopeParts = new ArrayList<>();
        byColumn.forEach((column, values) -> {
            where.append(" AND lower(").append(JSON).append("->'row'->>?) IN (")
                    .append(String.join(",", Collections.nCopies(values.size(), "?"))).append(")");
            whereParams.add(column);
            values.forEach(v -> whereParams.add(v.toLowerCase(Locale.ROOT)));
            scopeParts.add(column + " = " + String.join(" or ", values));
        });

        Integer total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + where, Integer.class, whereParams.toArray());
        if (total == null || total == 0) {
            return null;
        }
        List<Object> selectParams = new ArrayList<>(whereParams);
        selectParams.add(MAX_SCOPED_ROWS);
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT content FROM " + table + where
                        + " ORDER BY (" + JSON + "->>'rowNumber')::int LIMIT ?",
                String.class, selectParams.toArray());
        return new ScopedRows(rows, total, String.join(" AND ", scopeParts));
    }

    // --------------------------------------------------------------- aggregate

    /**
     * Exact calculation in SQL over ALL rows of the dataset (not just retrieved rows).
     * All column names must already be validated against the dataset headers by the caller;
     * they are still passed as bind parameters, never concatenated into the SQL.
     *
     * @param aggregation SUM, AVG, MIN, MAX or COUNT
     * @param metric      numeric column (ignored for COUNT)
     */
    public List<Map<String, Object>> aggregate(String datasetId, String aggregation, String metric,
                                               String groupBy, String filterColumn, String filterValue,
                                               boolean descending, int limit) {
        boolean count = "COUNT".equals(aggregation);
        String aggregateExpression = count ? "COUNT(*)" : aggregation + "(" + NUMBER_EXPR + ")";

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        if (groupBy != null) {
            sql.append(JSON).append("->'row'->>? AS grp, ");
            params.add(groupBy);
        }
        sql.append(aggregateExpression).append(" AS agg_value, COUNT(*) AS row_count FROM ").append(table);
        if (!count) {
            params.add(metric);
        }
        sql.append(" WHERE ").append(JSON).append("->>'datasetId' = ?");
        params.add(datasetId);
        if (filterColumn != null) {
            sql.append(" AND lower(").append(JSON).append("->'row'->>?) = lower(?)");
            params.add(filterColumn);
            params.add(filterValue);
        }
        if (groupBy != null) {
            sql.append(" GROUP BY 1");
        }
        sql.append(" ORDER BY agg_value ").append(descending ? "DESC" : "ASC").append(" NULLS LAST");
        if (groupBy != null) {
            sql.append(", 1");
        }
        sql.append(" LIMIT ?");
        params.add(groupBy != null ? limit : 1);

        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    /** Lowercase filename with unsafe characters replaced, e.g. "sales_and_finance_data.xlsx". */
    static String toDatasetId(String filename) {
        return filename.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }
}
