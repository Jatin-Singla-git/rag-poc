package com.example.rag_poc.ingest;

import com.example.rag_poc.store.DatasetInfo;
import com.example.rag_poc.store.DatasetStoreService;
import com.example.rag_poc.store.IndexResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestrates an upload: picks the Excel or PDF path by file extension, parses the file,
 * stores it through {@link DatasetStoreService} and builds the upload response.
 * <p>
 * Incremental indexing: the logical document is the dataset id derived from the filename; the SHA-256 of
 * the uploaded bytes decides what happens. A new file is indexed, a file with the same hash is skipped
 * (no parsing or embedding), and a file with the same name but another hash replaces the old content.
 */
@Service
public class IngestionService {

    private final ExcelDataService excelDataService;
    private final PdfDocumentService pdfDocumentService;
    private final DatasetStoreService vectorStoreService;

    public IngestionService(ExcelDataService excelDataService,
                            PdfDocumentService pdfDocumentService,
                            DatasetStoreService vectorStoreService) {
        this.excelDataService = excelDataService;
        this.pdfDocumentService = pdfDocumentService;
        this.vectorStoreService = vectorStoreService;
    }

    public Map<String, Object> ingest(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please upload a file.");
        }
        String name = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        boolean pdf = name.endsWith(".pdf");
        if (!pdf && !name.endsWith(".xlsx")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported file type. Please upload an .xlsx (Excel) or .pdf file.");
        }

        String contentHash = sha256(file);
        String datasetId = DatasetStoreService.toDatasetId(file.getOriginalFilename());
        if (vectorStoreService.hasContentHash(datasetId, contentHash)) {
            return unchangedResponse(datasetId, contentHash);
        }
        return pdf ? ingestPdf(file, contentHash) : ingestExcel(file, contentHash);
    }

    private Map<String, Object> ingestExcel(MultipartFile file, String contentHash) {
        ExcelData data = excelDataService.parse(file);
        IndexResult result = vectorStoreService.ingest(data, contentHash);

        List<Map<String, String>> preview =
                data.records().subList(0, Math.min(10, data.records().size()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filename", data.filename());
        response.put("sheetName", data.sheetName());
        response.put("totalRows", data.records().size());
        response.put("columns", data.headers());
        response.put("preview", preview);
        response.put("datasetId", result.datasetId());
        response.put("indexedRows", data.records().size());
        response.put("indexingStatus", status(result));
        response.put("contentHash", contentHash);
        response.put(
                "message",
                message(result, "Excel file read and stored in PostgreSQL (pgvector); it survives restarts. "
                        + "Uploading a file with the same name replaces its earlier data. "
                        + "You can now ask questions via POST /api/documents/ask.")
        );
        return response;
    }

    private Map<String, Object> ingestPdf(MultipartFile file, String contentHash) {
        PdfDocumentService.PdfData data = pdfDocumentService.parse(file);
        IndexResult result = vectorStoreService.ingestPdf(data, contentHash);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filename", data.filename());
        response.put("documentType", "pdf");
        response.put("totalPages", data.totalPages());
        response.put("extractedCharacters", data.extractedCharacters());
        response.put("datasetId", result.datasetId());
        response.put("indexedChunks", data.chunks().size());
        response.put("indexingStatus", status(result));
        response.put("contentHash", contentHash);
        response.put(
                "message",
                message(result, "PDF text extracted, split into chunks and stored in PostgreSQL (pgvector). "
                        + "Uploading a file with the same name replaces its earlier data. "
                        + "Ask questions via POST /api/documents/ask; the relevant file is chosen "
                        + "automatically unless you pass \"filename\".")
        );
        return response;
    }

    /**
     * Response when the identical content is already indexed (found before parsing). Parsing is skipped,
     * so only what is stored is reported: counts and, for Excel, the sheet and columns (no preview).
     */
    private Map<String, Object> unchangedResponse(String datasetId, String contentHash) {
        DatasetInfo info = vectorStoreService.loadDataset(datasetId);
        int parts = vectorStoreService.countParts(datasetId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filename", info.filename());
        if ("pdf".equals(info.type())) {
            response.put("documentType", "pdf");
            response.put("datasetId", datasetId);
            response.put("indexedChunks", parts);
        } else {
            response.put("sheetName", info.sheetName());
            response.put("totalRows", parts);
            response.put("columns", info.columns());
            response.put("datasetId", datasetId);
            response.put("indexedRows", parts);
        }
        response.put("indexingStatus", "unchanged");
        response.put("contentHash", contentHash);
        response.put("message", UNCHANGED_MESSAGE);
        return response;
    }

    private static final String UNCHANGED_MESSAGE =
            "This file is identical to the version already indexed; nothing was re-indexed.";

    private static String status(IndexResult result) {
        return result.status().name().toLowerCase(Locale.ROOT);
    }

    /** Keeps the existing message for new files; says so when old content was replaced or nothing changed. */
    private static String message(IndexResult result, String base) {
        return switch (result.status()) {
            case UNCHANGED -> UNCHANGED_MESSAGE;
            case UPDATED -> "The file content changed, so the earlier indexed version was replaced. " + base;
            case CREATED -> base;
        };
    }

    private static String sha256(MultipartFile file) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(file.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file.");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
