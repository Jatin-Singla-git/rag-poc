package com.example.rag_poc.store;

import java.time.Instant;
import java.util.UUID;

/** One conversation with enough metadata to identify it in a list. firstQuestion may be null. */
public record ConversationSummary(UUID conversationId, Instant createdAt, Instant updatedAt,
                                  int messageCount, String firstQuestion) {
}
