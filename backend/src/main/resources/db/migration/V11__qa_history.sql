-- 问答历史、引用与反馈：每个事实性回答都能回溯到 文件+版本+分块。
CREATE TABLE IF NOT EXISTS conversations (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_conversations_user ON conversations(user_id);

CREATE TABLE IF NOT EXISTS messages (
  id BIGSERIAL PRIMARY KEY,
  conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  role VARCHAR(20) NOT NULL,
  content TEXT NOT NULL,
  scope VARCHAR(20) NOT NULL DEFAULT 'ENTERPRISE',
  ok BOOLEAN NOT NULL DEFAULT true,
  source VARCHAR(40),
  confidence NUMERIC(4,3),
  ttfb_ms INT,
  total_ms INT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id);

CREATE TABLE IF NOT EXISTS citations (
  id BIGSERIAL PRIMARY KEY,
  message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  article_id BIGINT,
  version_id BIGINT,
  chunk_id BIGINT,
  note_id BIGINT,
  citation TEXT NOT NULL,
  title TEXT,
  snippet TEXT,
  heading_path TEXT,
  page_no INT,
  score NUMERIC(6,4),
  clicked BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_citations_message ON citations(message_id);

CREATE TABLE IF NOT EXISTS knowledge_feedback (
  id BIGSERIAL PRIMARY KEY,
  message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id),
  rating SMALLINT NOT NULL,
  reasons TEXT[],
  comment TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
