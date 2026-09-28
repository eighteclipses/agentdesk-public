-- Bind an original document to the exact version produced by its import.
-- No files or historical versions are deleted. Ambiguous legacy mappings remain NULL;
-- administrators can still retrieve those originals from the import center.
ALTER TABLE knowledge_versions ADD COLUMN source_import_item_id BIGINT
  REFERENCES import_items(id) ON DELETE SET NULL;
CREATE INDEX idx_knowledge_version_source ON knowledge_versions(source_import_item_id);

-- A single version and a single successful import is the only unambiguous legacy case.
UPDATE knowledge_versions v SET source_import_item_id=i.id
FROM import_items i
WHERE i.article_id=v.article_id AND i.status IN ('REVIEW','PUBLISHED','RETRACTED','REJECTED')
  AND (SELECT count(*) FROM knowledge_versions x WHERE x.article_id=v.article_id)=1
  AND (SELECT count(*) FROM import_items x WHERE x.article_id=v.article_id)=1;

-- Restore the old published version if a draft edit/rejection previously hid it.
-- Explicitly retracted (ARCHIVED) articles stay offline.
UPDATE knowledge_articles a SET status='PUBLISHED'
FROM knowledge_versions v
WHERE v.id=a.current_version_id AND v.status='PUBLISHED'
  AND a.status IN ('IN_REVIEW','REJECTED')
  AND EXISTS (SELECT 1 FROM knowledge_versions draft
              WHERE draft.article_id=a.id AND draft.version>v.version);
