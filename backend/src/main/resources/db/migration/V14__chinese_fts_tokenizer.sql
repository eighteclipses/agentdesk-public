-- 中文全文检索：以 CJK 单字 + 相邻双字分词替代 simple 配置。
-- simple 不切分中文，此前只能靠 LIKE '%..%' 全表扫描兜底；本方案纯 SQL 函数实现，
-- 不依赖 zhparser/pg_jieba 等扩展，任何 PostgreSQL 16 + UTF8 环境可用。
--
-- 文档侧（agentdesk_fts_doc_tokens）：CJK 连续段拆为单字与相邻双字（双字提升 ts_rank 精度），
--   拉丁/数字按小写词保留；查询侧（agentdesk_fts_query_tokens）：CJK 拆为单字（AND 语义），
--   拉丁词加 :* 前缀匹配。两侧经同一组函数保证分词一致，配合 to_tsquery 使用。

-- CJK 连续段 -> 单字 + 相邻双字
CREATE OR REPLACE FUNCTION agentdesk_cjk_tokens(run text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT coalesce(string_agg(tok, ' '), '')
  FROM (
    SELECT substr(run, g.n, 1) AS tok, g.n AS ord FROM generate_series(1, char_length(run)) g(n)
    UNION ALL
    SELECT substr(run, g.n, 2) AS tok, g.n + 100000 AS ord FROM generate_series(1, greatest(char_length(run) - 1, 0)) g(n)
  ) s
$$;

-- 文档分词：遍历字符，CJK 段转单字+双字，拉丁/数字段转小写词
CREATE OR REPLACE FUNCTION agentdesk_fts_doc_tokens(input text) RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE
  out text := '';
  cjk_run text := '';
  word text := '';
  ch text;
  code int;
  i int;
  n int;
BEGIN
  IF input IS NULL THEN RETURN ''; END IF;
  n := char_length(input);
  FOR i IN 1..n LOOP
    ch := substr(input, i, 1);
    code := ascii(ch);
    IF code BETWEEN 13312 AND 40959 THEN  -- U+3400..U+9FFF：CJK 扩展 A + 统一表意文字
      IF word <> '' THEN out := out || ' ' || word; word := ''; END IF;
      cjk_run := cjk_run || ch;
    ELSIF ch ~ '[A-Za-z0-9]' THEN
      IF cjk_run <> '' THEN out := out || ' ' || agentdesk_cjk_tokens(cjk_run); cjk_run := ''; END IF;
      word := word || lower(ch);
    ELSE
      IF word <> '' THEN out := out || ' ' || word; word := ''; END IF;
      IF cjk_run <> '' THEN out := out || ' ' || agentdesk_cjk_tokens(cjk_run); cjk_run := ''; END IF;
    END IF;
  END LOOP;
  IF word <> '' THEN out := out || ' ' || word; END IF;
  IF cjk_run <> '' THEN out := out || ' ' || agentdesk_cjk_tokens(cjk_run); END IF;
  RETURN btrim(out);
END;
$$;

-- 查询分词：CJK 单字（AND）+ 拉丁词前缀匹配；无有效词时返回 NULL（to_tsquery 不匹配）
CREATE OR REPLACE FUNCTION agentdesk_fts_query_tokens(input text) RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE
  parts text[] := ARRAY[]::text[];
  word text := '';
  ch text;
  code int;
  i int;
  n int;
BEGIN
  IF input IS NULL OR btrim(input) = '' THEN RETURN NULL; END IF;
  n := char_length(input);
  FOR i IN 1..n LOOP
    ch := substr(input, i, 1);
    code := ascii(ch);
    IF code BETWEEN 13312 AND 40959 THEN
      IF word <> '' THEN parts := parts || (word || ':*'); word := ''; END IF;
      parts := parts || ch;
    ELSIF ch ~ '[A-Za-z0-9]' THEN
      word := word || lower(ch);
    ELSE
      IF word <> '' THEN parts := parts || (word || ':*'); word := ''; END IF;
    END IF;
  END LOOP;
  IF word <> '' THEN parts := parts || (word || ':*'); END IF;
  IF cardinality(parts) = 0 THEN RETURN NULL; END IF;
  RETURN array_to_string(parts, ' & ');
END;
$$;

-- 重建三处生成列（删列会连带删除其上的 GIN 索引，随后重建）
ALTER TABLE knowledge_chunks DROP COLUMN search_vector;
ALTER TABLE knowledge_chunks ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (to_tsvector('simple', agentdesk_fts_doc_tokens(content))) STORED;
CREATE INDEX idx_chunks_fts ON knowledge_chunks USING GIN (search_vector);

ALTER TABLE knowledge_versions DROP COLUMN search_vector;
ALTER TABLE knowledge_versions ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (to_tsvector('simple', agentdesk_fts_doc_tokens(coalesce(content, '')))) STORED;
CREATE INDEX idx_knowledge_search ON knowledge_versions USING GIN (search_vector);

ALTER TABLE notes DROP COLUMN search_vector;
ALTER TABLE notes ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (to_tsvector('simple', agentdesk_fts_doc_tokens(coalesce(title, '') || ' ' || content))) STORED;
CREATE INDEX idx_notes_fts ON notes USING GIN (search_vector);
