package com.example.rag_poc.ask;

import com.example.rag_poc.store.ConversationMessage;
import com.example.rag_poc.store.ConversationStore;
import com.example.rag_poc.store.ConversationSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Conversation memory: id validation, the bounded history window, and saving messages. */
@Service
public class ConversationService {

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;

    private final ConversationStore store;
    private final int historyMessages;
    private final int historyMaxChars;

    public ConversationService(ConversationStore store,
                               @Value("${app.chat.history-messages:6}") int historyMessages,
                               @Value("${app.chat.history-max-chars:800}") int historyMaxChars) {
        this.store = store;
        this.historyMessages = historyMessages;
        this.historyMaxChars = historyMaxChars;
    }

    public ConversationSummary create() {
        return store.create();
    }

    public List<ConversationSummary> list(Integer limit) {
        int effective = limit == null ? DEFAULT_LIST_LIMIT : Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        return store.list(effective);
    }

    /** All messages of an existing conversation, oldest first. */
    public List<ConversationMessage> messages(String conversationId) {
        return store.messages(require(conversationId));
    }

    /** Validates the id: 400 if it is not a UUID, 404 if no such conversation exists. */
    public UUID require(String conversationId) {
        String trimmed = conversationId == null ? "" : conversationId.trim();
        UUID id;
        try {
            id = UUID.fromString(trimmed);
        } catch (IllegalArgumentException exception) {
            throw new ConversationException(HttpStatus.BAD_REQUEST,
                    "The conversationId is not a valid conversation ID.");
        }
        if (!id.toString().equals(trimmed.toLowerCase(Locale.ROOT))) {
            throw new ConversationException(HttpStatus.BAD_REQUEST,
                    "The conversationId is not a valid conversation ID.");
        }
        if (!store.exists(id)) {
            throw new ConversationException(HttpStatus.NOT_FOUND, "Conversation not found.");
        }
        return id;
    }

    /** The most recent messages (count and per-message length are configurable), oldest first. */
    public List<ConversationMessage> recentHistory(UUID conversationId) {
        if (historyMessages <= 0) {
            return List.of();
        }
        return store.recentMessages(conversationId, historyMessages).stream()
                .map(m -> m.content().length() <= historyMaxChars ? m
                        : new ConversationMessage(m.id(), m.role(),
                        m.content().substring(0, historyMaxChars) + "...", m.createdAt()))
                .toList();
    }

    /** Saves the user's question and the assistant's answer. */
    public void append(UUID conversationId, String question, String answer) {
        store.append(conversationId, question, answer == null ? "" : answer);
    }
}
