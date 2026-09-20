-- 异步导入流水线：批次 -> 批次内文件 -> 处理阶段事件（append-only，用于进度时间线与 SSE 重放）
CREATE TABLE IF NOT EXISTS import_batches (
  id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  created_by BIGINT NOT NULL REFERENCES users(id),
  department_id BIGINT REFERENCES departments(id),
  provider VARCHAR(60),
  total_items INT NOT NULL DEFAULT 0,
  status VARCHAR(30) NOT NULL DEFAULT 'PROCESSING',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS import_items (
  id BIGSERIAL PRIMARY KEY,
  batch_id BIGINT NOT NULL REFERENCES import_batches(id) ON DELETE CASCADE,
  file_name TEXT NOT NULL,
  content_type VARCHAR(120),
  size_bytes BIGINT NOT NULL DEFAULT 0,
  sha256 CHAR(64),
  parser TEXT,
  object_key VARCHAR(500) NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
  progress SMALLINT NOT NULL DEFAULT 0,
  error TEXT,
  summary TEXT,
  article_id BIGINT REFERENCES knowledge_articles(id) ON DELETE SET NULL,
  duplicate_of BIGINT REFERENCES import_items(id) ON DELETE SET NULL,
  created_by BIGINT NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_import_items_queued ON import_items(id) WHERE status = 'QUEUED';
CREATE INDEX IF NOT EXISTS idx_import_items_batch ON import_items(batch_id);
CREATE INDEX IF NOT EXISTS idx_import_items_sha ON import_items(sha256);

CREATE TABLE IF NOT EXISTS processing_events (
  id BIGSERIAL PRIMARY KEY,
  item_id BIGINT NOT NULL REFERENCES import_items(id) ON DELETE CASCADE,
  stage VARCHAR(30) NOT NULL,
  message TEXT,
  progress SMALLINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_processing_events_item ON processing_events(item_id);
