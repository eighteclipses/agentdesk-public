-- 知识库专项：检索 OR 放宽分词 + 问答反馈保留
-- 1) OR 分词：与 agentdesk_fts_query_tokens 同规则，连接符改用 |。
--    AND 检索（单字 AND）对长查询存在召回断崖（要求每个字都出现）；
--    AND 候选不足一页时由服务层用本函数放宽为 OR 并按 ts_rank 排序补足。
CREATE OR REPLACE FUNCTION agentdesk_fts_query_tokens_or(input text) RETURNS text
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
  RETURN array_to_string(parts, ' | ');
END;
$$;

-- 2) 反馈保留：删除会话/消息后反馈仍计入质量统计（对齐 QaHistoryService.deleteConversation 的注释语义）
ALTER TABLE knowledge_feedback ALTER COLUMN message_id DROP NOT NULL;
ALTER TABLE knowledge_feedback DROP CONSTRAINT knowledge_feedback_message_id_fkey;
ALTER TABLE knowledge_feedback ADD CONSTRAINT knowledge_feedback_message_id_fkey
  FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE SET NULL;
