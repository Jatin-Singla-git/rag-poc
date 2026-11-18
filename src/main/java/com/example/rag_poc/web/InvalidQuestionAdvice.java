package com.example.rag_poc.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Returns the validation message for an invalid question. Handles only {@link InvalidQuestionException};
 * every other error (404, 409, 502, ...) keeps Spring Boot's default error response.
 */
@RestControllerAdvice
class InvalidQuestionAdvice {

    @ExceptionHandler(InvalidQuestionException.class)
    ResponseEntity<Map<String, Object>> handle(InvalidQuestionException exception, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", HttpStatus.BAD_REQUEST.getReasonPhrase());
        body.put("message", exception.getMessage());
        body.put("path", request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }
}
