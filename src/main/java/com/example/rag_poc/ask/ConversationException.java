package com.example.rag_poc.ask;

import org.springframework.http.HttpStatus;

/** A client error about a conversation id (400 invalid id, 404 unknown conversation). */
public class ConversationException extends RuntimeException {

    private final HttpStatus status;

    public ConversationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
