package com.agentdesk.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class SlaScheduler {
  private final JdbcTemplate jdbc; private final AuditService audit; private final NotificationService notifications;
  public SlaScheduler(JdbcTemplate jdbc, AuditService audit, NotificationService notifications) { this.jdbc = jdbc; this.audit = audit; this.notifications = notifications; }
  @Scheduled(fixedDelay = 60000)
  public void recordBreaches() {
    List<Long> ids = jdbc.query("SELECT id FROM tickets WHERE status NOT IN ('CLOSED','RESOLVED') AND due_at < now() AND NOT EXISTS (SELECT 1 FROM audit_logs a WHERE a.action='SLA_BREACH' AND a.resource_id=tickets.id AND a.created_at > now()-interval '1 hour')", (rs,n)->rs.getLong(1));
    ids.forEach(id -> audit.log(null, "SLA_BREACH", "TICKET", id, java.util.Map.of("source", "scheduled-check")));
    notifyFirstBreaches();
  }

  /** 站内通知只在工单首次违约时发一次（按 notifications.ref 去重）：处理人优先，未指派则队列成员。 */
  private void notifyFirstBreaches() {
    List<Map<String,Object>> breaches = jdbc.queryForList(
        "SELECT t.id,t.title,t.assignee_id,t.queue_id FROM tickets t "
        + "WHERE t.status NOT IN ('CLOSED','RESOLVED') AND t.due_at < now() "
        + "AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.type='SLA_BREACH' AND n.ref_type='TICKET' AND n.ref_id=t.id)");
    for (Map<String,Object> row : breaches) {
      long id = ((Number) row.get("id")).longValue();
      String title = String.valueOf(row.get("title"));
      Long assignee = (Long) row.get("assignee_id"); Long queueId = (Long) row.get("queue_id");
      List<Long> recipients = assignee != null ? List.of(assignee) : queueAgents(queueId);
      notifications.notifyAll(recipients, "SLA_BREACH", "工单已超出 SLA 时限：「" + title + "」", "/workspace/agent", "TICKET", id, null);
    }
  }

  private List<Long> queueAgents(Long queueId) {
    if (queueId == null) return List.of();
    return jdbc.query("SELECT qm.user_id FROM queue_members qm JOIN users u ON u.id=qm.user_id "
        + "JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id "
        + "WHERE qm.queue_id=? AND u.active=true AND u.account_status='ACTIVE' AND r.code='AGENT'", (rs,n)->rs.getLong(1), queueId);
  }
}
