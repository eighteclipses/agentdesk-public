-- 个人知识库（Vault）：数据主体在服务端；Obsidian 通过 PAT 做选择性同步（P2）。
-- 个人检索直接复用 notes.search_vector，与企业 knowledge_chunks 物理隔离。
CREATE TABLE IF NOT EXISTS vaults (
  id BIGSERIAL PRIMARY KEY,
  owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  type VARCHAR(20) NOT NULL DEFAULT 'PERSONAL',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (owner_id, name)
);

CREATE TABLE IF NOT EXISTS notes (
  id BIGSERIAL PRIMARY KEY,
  vault_id BIGINT NOT NULL REFERENCES vaults(id) ON DELETE CASCADE,
  folder_path TEXT NOT NULL DEFAULT '',
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  tags TEXT[] NOT NULL DEFAULT '{}',
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  content_hash CHAR(64) NOT NULL,
  search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', coalesce(title,'') || ' ' || content)) STORED,
  created_by BIGINT NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_notes_vault ON notes(vault_id);
CREATE INDEX IF NOT EXISTS idx_notes_fts ON notes USING GIN (search_vector);
CREATE INDEX IF NOT EXISTS idx_notes_tags ON notes USING GIN (tags);

CREATE TABLE IF NOT EXISTS note_versions (
  id BIGSERIAL PRIMARY KEY,
  note_id BIGINT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
  version INT NOT NULL,
  content TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (note_id, version)
);

CREATE TABLE IF NOT EXISTS note_links (
  id BIGSERIAL PRIMARY KEY,
  src_note_id BIGINT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
  raw_target TEXT NOT NULL,
  dst_note_id BIGINT REFERENCES notes(id) ON DELETE SET NULL,
  anchor TEXT,
  resolved BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (src_note_id, raw_target)
);
CREATE INDEX IF NOT EXISTS idx_note_links_dst ON note_links(dst_note_id);

CREATE TABLE IF NOT EXISTS note_aliases (
  note_id BIGINT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
  alias TEXT NOT NULL,
  PRIMARY KEY (note_id, alias)
);

CREATE TABLE IF NOT EXISTS sync_changes (
  id BIGSERIAL PRIMARY KEY,
  note_id BIGINT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
  direction VARCHAR(10) NOT NULL,
  base_hash CHAR(64),
  new_hash CHAR(64),
  status VARCHAR(20) NOT NULL,
  client VARCHAR(60),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
