package com.example.rag_poc.web;

/** Thrown by {@link QuestionValidator} for a missing, blank or too long question (HTTP 400). */
class InvalidQuestionException extends RuntimeException {

    InvalidQuestionException(String message) {
        super(message);
    }
}
