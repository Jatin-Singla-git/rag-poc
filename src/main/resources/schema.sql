-- Conversation memory. Runs at every startup (spring.sql.init.mode=always); every statement is idempotent.
-- The vector-store table (excel_vector_store_gemini) is managed by Spring AI and is not touched here.

CREATE TABLE IF NOT EXISTS conversations (
    id         uuid        PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS conversation_messages (
    id              uuid        PRIMARY KEY,
    conversation_id uuid        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    seq             bigint      GENERATED ALWAYS AS IDENTITY,
    role            varchar(16) NOT NULL CHECK (role IN ('user', 'assistant')),
    content         text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- seq increases with every insert, so (conversation_id, seq) gives a stable order inside a conversation.
CREATE INDEX IF NOT EXISTS idx_conversation_messages_conversation_seq
    ON conversation_messages (conversation_id, seq);

CREATE INDEX IF NOT EXISTS idx_conversations_updated_at
    ON conversations (updated_at DESC);
