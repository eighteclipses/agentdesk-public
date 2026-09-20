-- 审核生命周期：状态机扩为 DRAFT/IN_REVIEW/PUBLISHED/ARCHIVED/REJECTED；
-- 版本具备独立状态，发布语义 = 新版本置 PUBLISHED 且切换 current_version_id，旧版本归档。
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS reviewed_by BIGINT REFERENCES users(id);
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS review_comment TEXT;
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS source_note_id BIGINT;
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS sensitivity VARCHAR(20) NOT NULL DEFAULT 'INTERNAL';

ALTER TABLE knowledge_versions ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE knowledge_versions ADD COLUMN IF NOT EXISTS chunk_ready BOOLEAN NOT NULL DEFAULT false;

-- 既有数据全部视为已发布版本
UPDATE knowledge_versions SET status='PUBLISHED' WHERE status <> 'PUBLISHED' AND id IN (SELECT current_version_id FROM knowledge_articles WHERE current_version_id IS NOT NULL);
-- 说明：旧版语句试图把 document_imports 为 READY 的文章置为 IN_REVIEW，但 document_imports
-- 没有 article_id 列（V5 建表时未包含），在全新库上必然报错，且该映射本就无法建立，故移除。
