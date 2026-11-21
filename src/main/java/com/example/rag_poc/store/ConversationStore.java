package com.example.rag_poc.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * PostgreSQL persistence for conversation memory (tables conversations and conversation_messages,
 * created by schema.sql). Uses the application's existing JdbcTemplate / data source.
 * Every query is scoped by conversation_id, so messages of different conversations never mix.
 */
@Service
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    private static final RowMapper<ConversationMessage> MESSAGE_MAPPER = (rs, i) -> new ConversationMessage(
            rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("content"),
            instant(rs, "created_at"));

    private final JdbcTemplate jdbcTemplate;

    public ConversationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ConversationSummary create() {
        UUID id = UUID.randomUUID();
        return db("create the conversation", () -> jdbcTemplate.queryForObject(
                "INSERT INTO conversations (id) VALUES (?) RETURNING created_at, updated_at",
                (rs, i) -> new ConversationSummary(id, instant(rs, "created_at"), instant(rs, "updated_at"), 0, null),
                id));
    }

    public boolean exists(UUID conversationId) {
        return Boolean.TRUE.equals(db("look up the conversation", () -> jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM conversations WHERE id = ?)", Boolean.class, conversationId)));
    }

    /** Newest updated first. */
    public List<ConversationSummary> list(int limit) {
        return db("list conversations", () -> jdbcTemplate.query(
                "SELECT c.id, c.created_at, c.updated_at,"
                        + " (SELECT count(*) FROM conversation_messages m WHERE m.conversation_id = c.id) AS message_count,"
                        + " (SELECT left(m.content, 80) FROM conversation_messages m"
                        + "   WHERE m.conversation_id = c.id AND m.role = 'user' ORDER BY m.seq LIMIT 1) AS first_question"
                        + " FROM conversations c ORDER BY c.updated_at DESC, c.id LIMIT ?",
                (rs, i) -> new ConversationSummary(rs.getObject("id", UUID.class),
                        instant(rs, "created_at"), instant(rs, "updated_at"),
                        rs.getInt("message_count"), rs.getString("first_question")),
                limit));
    }

    /** All messages of one conversation, oldest first. */
    public List<ConversationMessage> messages(UUID conversationId) {
        return db("read the messages", () -> jdbcTemplate.query(
                "SELECT id, role, content, created_at FROM conversation_messages"
                        + " WHERE conversation_id = ? ORDER BY seq",
                MESSAGE_MAPPER, conversationId));
    }

    /** The most recent {@code limit} messages of one conversation, oldest first. */
    public List<ConversationMessage> recentMessages(UUID conversationId, int limit) {
        List<ConversationMessage> newestFirst = db("read the conversation history", () -> jdbcTemplate.query(
                "SELECT id, role, content, created_at FROM conversation_messages"
                        + " WHERE conversation_id = ? ORDER BY seq DESC LIMIT ?",
                MESSAGE_MAPPER, conversationId, limit));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** Saves a question and its answer together (one transaction) and bumps updated_at. */
    @Transactional
    public void append(UUID conversationId, String userContent, String assistantContent) {
        db("save the conversation messages", () -> {
            insertMessage(conversationId, "user", userContent);
            insertMessage(conversationId, "assistant", assistantContent);
            return jdbcTemplate.update("UPDATE conversations SET updated_at = now() WHERE id = ?", conversationId);
        });
    }

    private void insertMessage(UUID conversationId, String role, String content) {
        jdbcTemplate.update(
                "INSERT INTO conversation_messages (id, conversation_id, role, content) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), conversationId, role, content);
    }

    /** Database errors are logged (without message content) and returned as a generic 500, like ingestion. */
    private <T> T db(String action, Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException exception) {
            log.error("Conversation store failed to {}", action, exception);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not " + action + ".");
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
