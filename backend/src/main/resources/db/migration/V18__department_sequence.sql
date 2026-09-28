-- V2 inserted department IDs explicitly; keep the next generated ID above existing rows.
-- Preserve any larger sequence value already consumed by this installation.
SELECT setval('departments_id_seq',
              GREATEST((SELECT COALESCE(MAX(id),1) FROM departments),
                       (SELECT last_value FROM departments_id_seq)), true);
