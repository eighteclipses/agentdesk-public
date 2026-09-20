-- 站内通知：工单流转/SLA 违约/Agent 审批结论/知识审核结果/账号开通等事件触达相关用户
CREATE TABLE IF NOT EXISTS notifications (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type VARCHAR(60) NOT NULL,
  title VARCHAR(240) NOT NULL,
  body TEXT,
  link VARCHAR(300),
  ref_type VARCHAR(60),
  ref_id BIGINT,
  read_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_notifications_user_time ON notifications(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_unread ON notifications(user_id) WHERE read_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_notifications_ref ON notifications(ref_type, ref_id);

-- SLA 达成统计口径：首次置 RESOLVED 的时间（重开清空，重新解决再写）
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ;
