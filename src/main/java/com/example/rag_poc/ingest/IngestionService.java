package com.example.rag_poc.ingest;

import com.example.rag_poc.store.DatasetStoreService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestrates an upload: picks the Excel or PDF path by file extension, parses the file,
 * stores it through {@link DatasetStoreService} and builds the upload response.
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
        if (name.endsWith(".pdf")) {
            return ingestPdf(file);
        }
        if (!name.endsWith(".xlsx")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported file type. Please upload an .xlsx (Excel) or .pdf file.");
        }
        return ingestExcel(file);
    }

    private Map<String, Object> ingestExcel(MultipartFile file) {
        ExcelData data = excelDataService.parse(file);
        String datasetId = vectorStoreService.ingest(data);

        List<Map<String, String>> preview =
                data.records().subList(0, Math.min(10, data.records().size()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filename", data.filename());
        response.put("sheetName", data.sheetName());
        response.put("totalRows", data.records().size());
        response.put("columns", data.headers());
        response.put("preview", preview);
        response.put("datasetId", datasetId);
        response.put("indexedRows", data.records().size());
        response.put(
                "message",
                "Excel file read and stored in PostgreSQL (pgvector); it survives restarts. "
                        + "Uploading a file with the same name replaces its earlier data. "
                        + "You can now ask questions via POST /api/documents/ask."
        );
        return response;
    }

    private Map<String, Object> ingestPdf(MultipartFile file) {
        PdfDocumentService.PdfData data = pdfDocumentService.parse(file);
        String datasetId = vectorStoreService.ingestPdf(data);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filename", data.filename());
        response.put("documentType", "pdf");
        response.put("totalPages", data.totalPages());
        response.put("extractedCharacters", data.extractedCharacters());
        response.put("datasetId", datasetId);
        response.put("indexedChunks", data.chunks().size());
        response.put(
                "message",
                "PDF text extracted, split into chunks and stored in PostgreSQL (pgvector). "
                        + "Uploading a file with the same name replaces its earlier data. "
                        + "Ask questions via POST /api/documents/ask; the relevant file is chosen "
                        + "automatically unless you pass \"filename\"."
        );
        return response;
    }
}
