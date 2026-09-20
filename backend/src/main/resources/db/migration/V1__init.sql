CREATE TABLE departments (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
  id BIGINT PRIMARY KEY,
  username VARCHAR(80) NOT NULL UNIQUE,
  display_name VARCHAR(120) NOT NULL,
  department_id BIGINT REFERENCES departments(id),
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE roles (id BIGSERIAL PRIMARY KEY, code VARCHAR(40) NOT NULL UNIQUE, name VARCHAR(80) NOT NULL);
CREATE TABLE user_roles (user_id BIGINT REFERENCES users(id) ON DELETE CASCADE, role_id BIGINT REFERENCES roles(id) ON DELETE CASCADE, PRIMARY KEY(user_id, role_id));

CREATE TABLE tickets (
  id BIGSERIAL PRIMARY KEY,
  requester_id BIGINT NOT NULL REFERENCES users(id),
  assignee_id BIGINT REFERENCES users(id),
  title VARCHAR(240) NOT NULL,
  description TEXT NOT NULL,
  category VARCHAR(60) NOT NULL DEFAULT 'OTHER',
  priority VARCHAR(10) NOT NULL DEFAULT 'P3',
  status VARCHAR(30) NOT NULL DEFAULT 'NEW',
  closed_reason TEXT,
  due_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tickets_requester ON tickets(requester_id);
CREATE INDEX idx_tickets_status ON tickets(status);

CREATE TABLE ticket_comments (
  id BIGSERIAL PRIMARY KEY,
  ticket_id BIGINT NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  author_id BIGINT NOT NULL REFERENCES users(id),
  content TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ticket_attachments (
  id BIGSERIAL PRIMARY KEY,
  ticket_id BIGINT NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  file_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(120),
  size_bytes BIGINT NOT NULL,
  object_key VARCHAR(500) NOT NULL,
  created_by BIGINT REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_articles (
  id BIGSERIAL PRIMARY KEY,
  title VARCHAR(240) NOT NULL,
  category VARCHAR(80) NOT NULL DEFAULT 'GENERAL',
  status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
  visibility VARCHAR(30) NOT NULL DEFAULT 'PUBLIC',
  current_version_id BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE knowledge_versions (
  id BIGSERIAL PRIMARY KEY,
  article_id BIGINT NOT NULL REFERENCES knowledge_articles(id) ON DELETE CASCADE,
  version INT NOT NULL,
  content TEXT NOT NULL,
  created_by BIGINT REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED,
  UNIQUE(article_id, version)
);
ALTER TABLE knowledge_articles ADD CONSTRAINT fk_current_knowledge_version FOREIGN KEY(current_version_id) REFERENCES knowledge_versions(id);
CREATE INDEX idx_knowledge_search ON knowledge_versions USING GIN(search_vector);
CREATE TABLE knowledge_tags (id BIGSERIAL PRIMARY KEY, name VARCHAR(80) NOT NULL UNIQUE);
CREATE TABLE article_tags (article_id BIGINT REFERENCES knowledge_articles(id) ON DELETE CASCADE, tag_id BIGINT REFERENCES knowledge_tags(id) ON DELETE CASCADE, PRIMARY KEY(article_id, tag_id));

CREATE TABLE agent_runs (
  id BIGSERIAL PRIMARY KEY,
  run_type VARCHAR(60) NOT NULL,
  ticket_id BIGINT REFERENCES tickets(id),
  input_summary TEXT NOT NULL,
  output_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  elapsed_ms BIGINT NOT NULL DEFAULT 0,
  error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE agent_actions (
  id BIGSERIAL PRIMARY KEY,
  action_type VARCHAR(60) NOT NULL,
  ticket_id BIGINT NOT NULL REFERENCES tickets(id),
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  created_by BIGINT REFERENCES users(id),
  decided_by BIGINT REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_at TIMESTAMPTZ
);

CREATE TABLE audit_logs (
  id BIGSERIAL PRIMARY KEY,
  actor_id BIGINT,
  action VARCHAR(80) NOT NULL,
  resource_type VARCHAR(60) NOT NULL,
  resource_id BIGINT NOT NULL,
  detail JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_created_at ON audit_logs(created_at DESC);
