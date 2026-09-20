-- P0-D 中文检索功能验证（子查询版本，不依赖 psql 变量）
INSERT INTO knowledge_articles(title, category, status, visibility) VALUES ('VPN 认证故障排查', 'NETWORK', 'PUBLISHED', 'PUBLIC');
INSERT INTO knowledge_versions(article_id, version, content)
SELECT id, 1, '# VPN 认证故障排查

## 现象
用户无法完成 VPN 登录。

## 处理步骤
1. 检查网络连通性
2. 重新认证
3. 查看客户端日志'
FROM knowledge_articles WHERE title = 'VPN 认证故障排查';
UPDATE knowledge_articles SET current_version_id = (SELECT id FROM knowledge_versions WHERE article_id = knowledge_articles.id) WHERE title = 'VPN 认证故障排查';
INSERT INTO knowledge_chunks(article_id, version_id, chunk_index, heading_path, content)
SELECT a.id, v.id, 0, 'VPN 认证故障排查 > 处理步骤', '检查网络连通性；重新认证；查看客户端日志。'
FROM knowledge_articles a JOIN knowledge_versions v ON v.article_id = a.id WHERE a.title = 'VPN 认证故障排查';

INSERT INTO vaults(owner_id, name) SELECT id, '验证用笔记' FROM users WHERE id = (SELECT min(id) FROM users) ON CONFLICT DO NOTHING;
INSERT INTO notes(vault_id, title, content, tags, content_hash, created_by)
SELECT v.id, '内网打印机共享', '三楼打印机共享需要管理员权限，先联系网络组开通。', '{}', 'x', v.owner_id
FROM vaults v WHERE v.name = '验证用笔记';

-- 3) 中文查询命中新文章
SELECT a.id, ts_rank(c.search_vector, to_tsquery('simple', agentdesk_fts_query_tokens('网络连通性'))) AS rank
FROM knowledge_chunks c JOIN knowledge_articles a ON a.id = c.article_id
WHERE a.status = 'PUBLISHED' AND c.search_vector @@ to_tsquery('simple', agentdesk_fts_query_tokens('网络连通性'))
ORDER BY rank DESC LIMIT 3;

-- 4) 中英文混合查询命中
SELECT count(*) AS mixed_hits FROM knowledge_chunks c
WHERE c.search_vector @@ to_tsquery('simple', agentdesk_fts_query_tokens('VPN 登录'));

-- 5) 无关词不应命中企业分块
SELECT count(*) AS must_be_zero FROM knowledge_chunks c
WHERE c.search_vector @@ to_tsquery('simple', agentdesk_fts_query_tokens('打印机共享'));

-- 6) 个人笔记检索命中
SELECT n.id, n.title FROM notes n
WHERE n.search_vector @@ to_tsquery('simple', agentdesk_fts_query_tokens('打印机权限'))
LIMIT 3;
