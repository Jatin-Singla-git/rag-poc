package com.example.rag_poc.web;

import com.example.rag_poc.ask.QuestionService;
import com.example.rag_poc.ingest.IngestionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IngestionService ingestionService;
    private final QuestionService questionService;

    public DocumentController(IngestionService ingestionService, QuestionService questionService) {
        this.ingestionService = ingestionService;
        this.questionService = questionService;
    }

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Map<String, Object> uploadExcel(
            @RequestParam("file") MultipartFile file) {
        return ingestionService.ingest(file);
    }

    @PostMapping("/ask")
    public Map<String, String> askAboutExcel(@RequestBody Map<String, String> request) {
        String question = QuestionValidator.validate(request == null ? null : request.get("question"));

        // Optional "filename" targets a specific upload; if omitted the relevant file(s) are selected automatically.
        // Optional "conversationId" keeps follow-up questions in the same stored conversation.
        String conversationId = request.get("conversationId");
        QuestionService.ExcelAnswer result =
                questionService.ask(question, request.get("filename"), conversationId);

        Map<String, String> response = new LinkedHashMap<>();
        response.put("question", question);
        if (conversationId != null && !conversationId.isBlank()) {
            response.put("conversationId", conversationId.trim());
        }
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
