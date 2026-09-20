package com.agentdesk.service;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TicketWorkflowTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final AuditService audit = mock(AuditService.class);
  private final NotificationService notifications = mock(NotificationService.class);
  private final TicketService service = new TicketService(jdbc, mock(AuthContext.class), audit, mock(StorageService.class), notifications);
  private final UserContext employee = new UserContext(2L,"employee","员工","EMPLOYEE",1L,"IT",List.of());
  private final UserContext agent = new UserContext(3L,"agent","处理人","AGENT",1L,"IT",List.of(7L));
  private Ticket ticket(String status, Long assignee) {
    return new Ticket(1L,2L,assignee,"VPN","无法连接","NETWORK","P3",status,1L,"IT",7L,"NETWORK","网络",Instant.now(),Instant.now(),Instant.now(),"员工","处理人");
  }
  @SuppressWarnings("unchecked") private void stubTicket(String status, Long assignee) {
    when(jdbc.query(contains("WHERE t.id=?"),any(RowMapper.class),eq(1L))).thenReturn(List.of(ticket(status,assignee)));
  }
  @Test void requesterCannotSmuggleAssigneeThroughReopen() {
    stubTicket("RESOLVED",3L);
    var e=assertThrows(ResponseStatusException.class,()->service.transition(1L,new TransitionRequest("IN_PROGRESS","仍有问题",4L),employee));
    assertEquals(403,e.getStatusCode().value());
    verify(jdbc,never()).update(anyString(),any(Object[].class));
  }
  @Test void resolutionIsWrittenToEmployeeVisibleTimeline() {
    stubTicket("IN_PROGRESS",3L);
    service.transition(1L,new TransitionRequest("RESOLVED","已修复 VPN",null),agent);
    verify(jdbc).update(contains("INSERT INTO ticket_comments"),eq(1L),eq(3L),eq("[已解决] 已修复 VPN"));
    verify(notifications).notify(eq(2L),eq("TICKET_STATUS_CHANGED"),contains("已解决"),eq("/workspace/tickets?ticket=1"),eq("TICKET"),eq(1L),isNull());
  }
  @Test void activeAgentCanStartNewTicketAndClaimIt() {
    stubTicket("NEW",null);
    when(jdbc.queryForObject(contains("SELECT count(*) FROM queue_members"),eq(Integer.class),eq(7L),eq(3L))).thenReturn(1);
    when(jdbc.queryForObject(contains("SELECT count(*) FROM users"),eq(Integer.class),eq(3L))).thenReturn(1);
    service.transition(1L,new TransitionRequest("IN_PROGRESS",null,null),agent);
    verify(jdbc).update(contains("UPDATE tickets SET status=?"),eq("IN_PROGRESS"),eq(3L),eq("IN_PROGRESS"),isNull(),eq("IN_PROGRESS"),eq("IN_PROGRESS"),eq(1L));
  }
  @Test void invalidPriorityFailsBeforeDatabaseWork() {
    var e=assertThrows(ResponseStatusException.class,()->service.create(new TicketCreateRequest("title","body","NETWORK","P99"),employee));
    assertEquals(400,e.getStatusCode().value());
    verifyNoInteractions(jdbc);
  }
  @Test void employeeSearchKeepsAccessScopeAndBindsUntrustedText() {
    service.list(employee,1,20,null,null,"' OR 1=1 --","open","due");
    verify(jdbc).queryForObject(contains("WHERE t.requester_id=? AND (strpos"),eq(Long.class),eq(2L),eq("' OR 1=1 --"),eq("' OR 1=1 --"),eq("' OR 1=1 --"));
  }
  @Test void agentWithoutQueuesCannotSearchOtherTickets() {
    var noQueues = new UserContext(4L,"other","无队列","AGENT",1L,"IT",List.of());
    assertEquals(0,service.list(noQueues,1,20,null,null,"VPN",null,null).total());
    verifyNoInteractions(jdbc);
  }
  @Test @SuppressWarnings("unchecked") void largePageOffsetDoesNotOverflow() {
    service.list(employee,Integer.MAX_VALUE,100,null,null,null,null,"created");
    verify(jdbc).query(contains("ORDER BY t.created_at DESC,t.id DESC LIMIT ? OFFSET ?"),any(RowMapper.class),eq(2L),eq(100),eq(214748364600L));
  }
}
