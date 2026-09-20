package com.agentdesk.service;

import com.agentdesk.api.ApiModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentActionStateTest {
  private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
  private final TicketService tickets=mock(TicketService.class);
  private final AgentService service=new AgentService(jdbc,new ObjectMapper(),mock(AuditService.class),mock(KnowledgeService.class),mock(VaultService.class),tickets,mock(NotificationService.class),"http://127.0.0.1:1","test");
  private final UserContext agent=new UserContext(3L,"agent","Agent","AGENT",1L,"IT",List.of(7L));
  private Ticket ticket(String status) {
    return new Ticket(1L,2L,3L,"title","description","NETWORK","P3",status,1L,"IT",7L,"NETWORK","Network",Instant.now(),Instant.now(),Instant.now(),"Requester","Agent");
  }
  @Test void closeSuggestionCannotBypassResolution() {
    when(tickets.lock(1L,agent)).thenReturn(ticket("NEW"));
    var error=assertThrows(ResponseStatusException.class,()->service.createAction(new AgentActionRequest("CLOSE",1L,Map.of()),agent));
    assertEquals(409,error.getStatusCode().value());
    verifyNoInteractions(jdbc);
  }
  @Test void transferCannotReopenClosedTicket() {
    when(tickets.lock(1L,agent)).thenReturn(ticket("CLOSED"));
    var error=assertThrows(ResponseStatusException.class,()->service.createAction(new AgentActionRequest("TRANSFER",1L,Map.of("queueCode","GENERAL")),agent));
    assertEquals(409,error.getStatusCode().value());
    verifyNoInteractions(jdbc);
  }
}
