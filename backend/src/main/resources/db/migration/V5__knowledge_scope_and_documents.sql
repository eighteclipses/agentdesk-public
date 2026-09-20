CREATE TABLE IF NOT EXISTS knowledge_department_access (
  article_id BIGINT NOT NULL REFERENCES knowledge_articles(id) ON DELETE CASCADE,
  department_id BIGINT NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
  PRIMARY KEY(article_id, department_id)
);

CREATE TABLE IF NOT EXISTS document_imports (
  id BIGSERIAL PRIMARY KEY,
  file_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(120),
  size_bytes BIGINT NOT NULL,
  object_key VARCHAR(500) NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'UPLOADED',
  summary TEXT,
  error TEXT,
  created_by BIGINT REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_department_access ON knowledge_department_access(department_id);
