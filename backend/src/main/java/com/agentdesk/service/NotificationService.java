package com.agentdesk.service;

import com.agentdesk.api.ApiModels.NotificationItem;
import com.agentdesk.api.ApiModels.NotificationPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** 站内通知：插入与已读状态维护，全部查询以用户本人为界；事件去重（如 SLA 首次违约）由触发方负责。 */
@Service
public class NotificationService {
  private final JdbcTemplate jdbc;
  public NotificationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  /** 触达单个用户；refType/refId 记录业务关联（如 TICKET/12）供前端与去重使用。 */
  public void notify(long userId, String type, String title, String link, String refType, Long refId, String body) {
    if (userId <= 0) return;
    jdbc.update("INSERT INTO notifications(user_id,type,title,body,link,ref_type,ref_id) VALUES (?,?,?,?,?,?,?)",
        userId, type, truncate(title, 240), body, link, refType, refId);
  }

  /** 批量触达，自动去重同一用户（如队列成员通知）。 */
  public void notifyAll(List<Long> userIds, String type, String title, String link, String refType, Long refId, String body) {
    if (userIds == null) return;
    userIds.stream().filter(id -> id != null && id > 0).distinct()
        .forEach(id -> notify(id, type, title, link, refType, refId, body));
  }

  public long unreadCount(long userId) {
    Long n = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND read_at IS NULL", Long.class, userId);
    return n == null ? 0 : n;
  }

  public NotificationPage list(long userId, int page, int size, boolean unreadOnly) {
    int p = Math.max(1, page); int s = Math.min(Math.max(1, size), 100);
    String where = " WHERE user_id=?" + (unreadOnly ? " AND read_at IS NULL" : "");
    Long total = jdbc.queryForObject("SELECT count(*) FROM notifications" + where, Long.class, userId);
    List<NotificationItem> items = jdbc.query(
        "SELECT id,type,title,body,link,ref_type,ref_id,read_at IS NOT NULL AS is_read,created_at FROM notifications" + where
            + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
        (rs, n) -> new NotificationItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), (Long) rs.getObject(7), rs.getBoolean(8), rs.getTimestamp(9).toInstant()),
        userId, s, (p - 1) * s);
    return new NotificationPage(items, total == null ? 0 : total, unreadCount(userId));
  }

  public void markRead(long userId, long id) {
    jdbc.update("UPDATE notifications SET read_at=now() WHERE id=? AND user_id=? AND read_at IS NULL", id, userId);
  }

  public void markAllRead(long userId) {
    jdbc.update("UPDATE notifications SET read_at=now() WHERE user_id=? AND read_at IS NULL", userId);
  }

  private static String truncate(String s, int max) {
    if (s == null || s.length() <= max) return s;
    return s.substring(0, max - 1) + "…";
  }
}
