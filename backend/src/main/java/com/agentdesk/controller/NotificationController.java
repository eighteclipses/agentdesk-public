package com.agentdesk.controller;

import com.agentdesk.api.ApiModels.NotificationPage;
import com.agentdesk.security.AuthContext;
import com.agentdesk.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
  private final NotificationService service; private final AuthContext auth;
  public NotificationController(NotificationService service, AuthContext auth) { this.service = service; this.auth = auth; }

  @GetMapping
  public NotificationPage list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                               @RequestParam(required = false) Boolean unread, HttpServletRequest req) {
    return service.list(auth.current(req).id(), page == null ? 1 : page, size == null ? 10 : size, Boolean.TRUE.equals(unread));
  }
  @GetMapping("/unread-count")
  public Map<String, Long> unreadCount(HttpServletRequest req) { return Map.of("count", service.unreadCount(auth.current(req).id())); }
  @PostMapping("/{id}/read")
  public Map<String, String> markRead(@PathVariable long id, HttpServletRequest req) { service.markRead(auth.current(req).id(), id); return Map.of("ok", "true"); }
  @PostMapping("/read-all")
  public Map<String, String> markAllRead(HttpServletRequest req) { service.markAllRead(auth.current(req).id()); return Map.of("ok", "true"); }
}
