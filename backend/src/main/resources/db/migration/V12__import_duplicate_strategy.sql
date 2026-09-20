-- 批次级重复策略：skip（同 sha256 跳过）| version（同 sha256 作为新版本导入）
ALTER TABLE import_batches ADD COLUMN IF NOT EXISTS duplicate_strategy VARCHAR(10) NOT NULL DEFAULT 'skip';
