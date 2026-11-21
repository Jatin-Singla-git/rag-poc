package com.example.rag_poc.web;

import com.example.rag_poc.ask.ConversationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Returns the message for client errors about the question (400) or the conversation id (400/404).
 * Handles only {@link InvalidQuestionException} and {@link ConversationException}; every other error
 * (file 404, 409, 502, ...) keeps Spring Boot's default error response.
 */
@RestControllerAdvice
class InvalidQuestionAdvice {

    @ExceptionHandler(InvalidQuestionException.class)
    ResponseEntity<Map<String, Object>> handle(InvalidQuestionException exception, HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(ConversationException.class)
    ResponseEntity<Map<String, Object>> handle(ConversationException exception, HttpServletRequest request) {
        return body(exception.getStatus(), exception.getMessage(), request);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
