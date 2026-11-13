package com.example.rag_poc;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class ExcelUploadController {

    private final ExcelDataService excelDataService;
    private final ExcelVectorStoreService vectorStoreService;
    private final PdfDocumentService pdfDocumentService;
    private final ExcelQuestionService excelQuestionService;

    public ExcelUploadController(ExcelDataService excelDataService,
                                 ExcelVectorStoreService vectorStoreService,
                                 PdfDocumentService pdfDocumentService,
                                 ExcelQuestionService excelQuestionService) {
        this.excelDataService = excelDataService;
        this.vectorStoreService = vectorStoreService;
        this.pdfDocumentService = pdfDocumentService;
        this.excelQuestionService = excelQuestionService;
    }

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Map<String, Object> uploadExcel(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please upload a file.");
        }
        String name = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return uploadPdf(file);
        }
        if (!name.endsWith(".xlsx")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported file type. Please upload an .xlsx (Excel) or .pdf file.");
        }

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

    private Map<String, Object> uploadPdf(MultipartFile file) {
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

    @PostMapping("/ask")
    public Map<String, String> askAboutExcel(@RequestBody Map<String, String> request) {
        String question = request == null ? null : request.get("question");

        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The question must not be blank.");
        }

        // Optional "filename" targets a specific upload; if omitted the relevant file(s) are selected automatically.
        ExcelQuestionService.ExcelAnswer result =
                excelQuestionService.ask(question.trim(), request.get("filename"));

        Map<String, String> response = new LinkedHashMap<>();
        response.put("question", question.trim());
        response.put("answer", result.answer());
        response.put("sourceFilename", result.filename());
        if (result.selection() != null) {
            response.put("selection", result.selection());
            if (result.selectionReason() != null) {
                response.put("selectionReason", result.selectionReason());
            }
        }
        response.put("mode", result.mode());
        if (result.sourcePages() != null) {
            response.put("sourcePages", result.sourcePages());
        }
        return response;
    }
}
