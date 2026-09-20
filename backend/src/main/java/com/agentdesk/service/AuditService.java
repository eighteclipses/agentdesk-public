package com.agentdesk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class AuditService {
  private final JdbcTemplate jdbc; private final ObjectMapper mapper;
  public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
  public void log(Long actorId, String action, String resourceType, long resourceId, Object detail) {
    try { jdbc.update("INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,detail) VALUES (?,?,?,?,?::jsonb)", actorId, action, resourceType, resourceId, mapper.writeValueAsString(detail == null ? Map.of() : detail)); }
    catch (JsonProcessingException e) { throw new IllegalStateException("审计内容序列化失败", e); }
  }
}
