package com.example.rag_poc.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared validation of the "question" field for /api/documents/ask and /api/chat. */
final class QuestionValidator {

    private static final Logger log = LoggerFactory.getLogger(QuestionValidator.class);

    static final int MAX_QUESTION_LENGTH = 2000;

    private QuestionValidator() {
    }

    /**
     * Returns the trimmed question, or throws {@link InvalidQuestionException} (HTTP 400) if it is
     * missing, blank or too long. Only the reason and the length are logged, never the question.
     */
    static String validate(String question) {
        if (question == null || question.isBlank()) {
            log.warn("Question rejected: blank or missing (length={})", question == null ? 0 : question.length());
            throw new InvalidQuestionException("The question must not be blank.");
        }
        String trimmed = question.trim();
        if (trimmed.length() > MAX_QUESTION_LENGTH) {
            log.warn("Question rejected: too long (length={}, max={})", trimmed.length(), MAX_QUESTION_LENGTH);
            throw new InvalidQuestionException(
                    "The question must not exceed " + MAX_QUESTION_LENGTH + " characters.");
        }
        return trimmed;
    }
}
