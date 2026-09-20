-- 补充验证：中英文混合查询（分块需真实包含这些词）
INSERT INTO knowledge_chunks(article_id, version_id, chunk_index, heading_path, content)
SELECT a.id, v.id, 1, 'VPN 认证故障排查 > 现象', 'VPN 登录失败时，先检查网络连通性，再重新认证。'
FROM knowledge_articles a JOIN knowledge_versions v ON v.article_id = a.id
WHERE a.title = 'VPN 认证故障排查' AND NOT EXISTS (
  SELECT 1 FROM knowledge_chunks c WHERE c.version_id = v.id AND c.chunk_index = 1
);

SELECT count(*) AS mixed_hits FROM knowledge_chunks c
WHERE c.search_vector @@ to_tsquery('simple', agentdesk_fts_query_tokens('VPN 登录'));

SELECT count(*) AS mixed_hits_fail FROM knowledge_chunks c
WHERE c.search_vector @@ to_tsquery('simple', agentdesk_fts_query_tokens('VPN 打印机'));

-- 种子数据（V2 的三篇已发布文章）中文召回抽查
SELECT a.title FROM knowledge_chunks c JOIN knowledge_articles a ON a.id = c.article_id
WHERE a.status = 'PUBLISHED' AND c.search_vector @@ to_tsquery('simple', agentdesk_fts_query_tokens('密码'))
LIMIT 5;
