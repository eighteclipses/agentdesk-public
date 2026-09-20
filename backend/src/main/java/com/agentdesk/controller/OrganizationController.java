package com.agentdesk.controller;

import com.agentdesk.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin/organization")
public class OrganizationController {
  private final JdbcTemplate jdbc; private final AuthContext auth;
  public OrganizationController(JdbcTemplate jdbc, AuthContext auth) { this.jdbc = jdbc; this.auth = auth; }
  @GetMapping public Map<String,Object> overview(HttpServletRequest req) {
    auth.requireAdmin(auth.current(req)); Map<String,Object> result = new LinkedHashMap<>();
    result.put("departments", jdbc.queryForList("SELECT id,name,created_at FROM departments ORDER BY id"));
    result.put("users", jdbc.queryForList("SELECT u.id,u.username,u.display_name,d.name AS department,u.active FROM users u LEFT JOIN departments d ON d.id=u.department_id ORDER BY u.id"));
    result.put("roles", jdbc.queryForList("SELECT id,code,name FROM roles ORDER BY id"));
    result.put("permissions", List.of("TICKET_READ_OWN", "TICKET_WRITE", "KNOWLEDGE_READ_AUTHORIZED", "KNOWLEDGE_PUBLISH", "AUDIT_READ", "AGENT_APPROVE"));
    return result;
  }
}
