package com.example.rag_poc.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String question = QuestionValidator.validate(request == null ? null : request.get("question"));

        String answer = chatClient.prompt()
                .user(question)
                .call()
                .content();

        return Map.of("question", question, "answer", answer);
    }
}
