package com.example.rag_poc.store;

import java.time.Instant;
import java.util.UUID;

/** One stored chat message. role is "user" or "assistant". */
public record ConversationMessage(UUID id, String role, String content, Instant createdAt) {
}
