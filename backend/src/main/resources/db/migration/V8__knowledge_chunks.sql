-- 知识分块：结构化切片是检索与可点击引用（article:x/version:y#cN）的基本单位。
-- 向量列为 P1 混合检索预留；未计算 embedding 前检索只走 FTS。
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS knowledge_chunks (
  id BIGSERIAL PRIMARY KEY,
  article_id BIGINT NOT NULL REFERENCES knowledge_articles(id) ON DELETE CASCADE,
  version_id BIGINT NOT NULL REFERENCES knowledge_versions(id) ON DELETE CASCADE,
  chunk_index INT NOT NULL,
  heading_path TEXT,
  content TEXT NOT NULL,
  page_no INT,
  token_count INT,
  search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
  embedding vector(512),
  embedding_model VARCHAR(120),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (version_id, chunk_index)
);
CREATE INDEX IF NOT EXISTS idx_chunks_fts ON knowledge_chunks USING GIN (search_vector);
CREATE INDEX IF NOT EXISTS idx_chunks_article ON knowledge_chunks(article_id, version_id);
CREATE INDEX IF NOT EXISTS idx_chunks_vec ON knowledge_chunks USING HNSW (embedding vector_cosine_ops);
