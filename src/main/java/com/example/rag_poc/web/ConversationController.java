package com.example.rag_poc.web;

import com.example.rag_poc.ask.ConversationService;
import com.example.rag_poc.store.ConversationMessage;
import com.example.rag_poc.store.ConversationSummary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create() {
        ConversationSummary created = conversationService.create();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", created.conversationId().toString());
        response.put("createdAt", created.createdAt().toString());
        response.put("updatedAt", created.updatedAt().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(value = "limit", required = false) Integer limit) {
        return conversationService.list(limit).stream().map(c -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("conversationId", c.conversationId().toString());
            item.put("createdAt", c.createdAt().toString());
            item.put("updatedAt", c.updatedAt().toString());
            item.put("messageCount", c.messageCount());
            item.put("firstQuestion", c.firstQuestion());
            return item;
        }).toList();
    }

    @GetMapping("/{conversationId}/messages")
    public Map<String, Object> messages(@PathVariable("conversationId") String conversationId) {
        List<ConversationMessage> messages = conversationService.messages(conversationId);
        List<Map<String, Object>> items = messages.stream().map(m -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("messageId", m.id().toString());
            item.put("role", m.role());
            item.put("content", m.content());
            item.put("createdAt", m.createdAt().toString());
            return item;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversationId.trim().toLowerCase(Locale.ROOT));
        response.put("messages", items);
        return response;
    }
}
