package com.agentdesk.service;

import com.agentdesk.api.ApiModels.NotificationItem;
import com.agentdesk.api.ApiModels.NotificationPage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final NotificationService service = new NotificationService(jdbc);

  @Test void notifyInsertsNotification() {
    service.notify(7L, "TICKET_ASSIGNED", "工单已指派给您：打印机故障", "/workspace/agent", "TICKET", 42L, null);
    verify(jdbc).update(contains("INSERT INTO notifications"), eq(7L), eq("TICKET_ASSIGNED"),
        eq("工单已指派给您：打印机故障"), isNull(), eq("/workspace/agent"), eq("TICKET"), eq(42L));
  }

  @Test void notifyIgnoresInvalidUserId() {
    service.notify(0L, "TICKET_ASSIGNED", "无主通知", null, null, null, null);
    service.notify(-1L, "TICKET_ASSIGNED", "无主通知", null, null, null, null);
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test void notifyAllDeduplicatesUsers() {
    service.notifyAll(java.util.Arrays.asList(3L, 3L, null, 0L, 5L), "SLA_BREACH", "工单超时", "/workspace/agent", "TICKET", 9L, null);
    verify(jdbc, times(2)).update(contains("INSERT INTO notifications"), anyLong(), eq("SLA_BREACH"),
        eq("工单超时"), isNull(), eq("/workspace/agent"), eq("TICKET"), eq(9L));
  }

  @Test void longTitleIsTruncatedWithEllipsis() {
    String longTitle = "长".repeat(300);
    service.notify(1L, "X", longTitle, null, null, null, null);
    verify(jdbc).update(contains("INSERT INTO notifications"), eq(1L), eq("X"),
        argThat((String t) -> t != null && t.length() == 240 && t.endsWith("…")), isNull(), isNull(), isNull(), isNull());
  }

  @Test void unreadCountFallsBackToZero() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(null);
    assertEquals(0, service.unreadCount(7L));
  }

  @Test @SuppressWarnings("unchecked") void listScopesEveryQueryToOwner() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(2L);
    when(jdbc.query(contains("FROM notifications"), any(RowMapper.class), eq(7L), eq(10), eq(0)))
        .thenReturn(List.of(new NotificationItem(1L, "TICKET_ASSIGNED", "标题", null, "/workspace/agent", "TICKET", 1L, false, java.time.Instant.now())));
    NotificationPage page = service.list(7L, 1, 10, false);
    assertEquals(1, page.items().size());
    assertEquals(2, page.total());
    assertEquals(2, page.unread());
    // WHERE 恒定以 user_id 为界，保证任何分页/过滤参数都不会越权读到他人通知
    verify(jdbc).query(contains("WHERE user_id=?"), any(RowMapper.class), eq(7L), eq(10), eq(0));
  }

  @Test void markReadIsScopedToOwnerAndUnread() {
    service.markRead(7L, 3L);
    verify(jdbc).update(contains("read_at=now() WHERE id=? AND user_id=? AND read_at IS NULL"), eq(3L), eq(7L));
  }

  @Test void markAllReadOnlyTouchesOwnNotifications() {
    service.markAllRead(7L);
    verify(jdbc).update(contains("WHERE user_id=? AND read_at IS NULL"), eq(7L));
  }
}
