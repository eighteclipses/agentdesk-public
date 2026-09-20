-- 导入批次原子性配套：保留文件夹相对路径（前端 webkitRelativePath）
ALTER TABLE import_items ADD COLUMN IF NOT EXISTS source_path TEXT;

-- token_count 历史上存的是字符数；改名为 char_count 以符合语义（字符计数）
ALTER TABLE knowledge_chunks RENAME COLUMN token_count TO char_count;
