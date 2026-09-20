package com.agentdesk.security;

import com.agentdesk.api.ApiModels.AuthUser;
import com.agentdesk.api.ApiModels.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthContextTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final JwtService jwt = mock(JwtService.class);
  private final AuthContext auth = new AuthContext(jdbc, jwt);

  private void stubActiveUser(long id, String role) {
    when(jdbc.queryForObject(eq("SELECT force_password_change FROM users WHERE id=?"), eq(Boolean.class), eq(id))).thenReturn(false);
    when(jdbc.queryForObject(argThat((String sql) -> sql != null && sql.contains("FROM users u")), any(org.springframework.jdbc.core.RowMapper.class), eq(id)))
        .thenReturn(new AuthUser(id, "u" + id, "用户" + id, role, 1L, "信息技术部", "ACTIVE", false, List.of()));
    when(jdbc.query(eq("SELECT queue_id FROM queue_members WHERE user_id=? ORDER BY queue_id"), any(org.springframework.jdbc.core.RowMapper.class), eq(id))).thenReturn(List.of());
  }

  @Test void missingTokenIsUnauthorized() {
    when(jwt.readToken(any(HttpServletRequest.class))).thenReturn(null);
    ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> auth.current(new MockHttpServletRequest()));
    assertEquals(401, ex.getStatusCode().value());
  }

  @Test void validTokenLoadsUserFromDatabase() {
    when(jwt.readToken(any(HttpServletRequest.class))).thenReturn("t");
    when(jwt.verify("t")).thenReturn(7L);
    stubActiveUser(7L, "EMPLOYEE");
    UserContext user = auth.current(new MockHttpServletRequest());
    assertEquals(7L, user.id());
    assertEquals("EMPLOYEE", user.role());
    assertFalse(user.admin());
    assertFalse(user.agent());
  }

  @Test void userIdHeaderIsNotTrusted() {
    // 历史上无参构造器会信任 X-User-Id 头；后门移除后该头必须无效
    when(jwt.readToken(any(HttpServletRequest.class))).thenReturn(null);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-User-Id", "10000");
    assertThrows(ResponseStatusException.class, () -> auth.current(request));
  }

  @Test void adminRoleIsEnforcedFromDatabaseRole() {
    when(jwt.readToken(any(HttpServletRequest.class))).thenReturn("t");
    when(jwt.verify("t")).thenReturn(1L);
    stubActiveUser(1L, "ADMIN");
    assertTrue(auth.current(new MockHttpServletRequest()).admin());
  }

  @Test void firstLoginMustChangePassword() {
    when(jwt.readToken(any(HttpServletRequest.class))).thenReturn("t");
    when(jwt.verify("t")).thenReturn(9L);
    when(jdbc.queryForObject(eq("SELECT force_password_change FROM users WHERE id=?"), eq(Boolean.class), eq(9L))).thenReturn(true);
    when(jdbc.queryForObject(argThat((String sql) -> sql != null && sql.contains("FROM users u")), any(org.springframework.jdbc.core.RowMapper.class), eq(9L)))
        .thenReturn(new AuthUser(9L, "u9", "用户9", "EMPLOYEE", 1L, "信息技术部", "ACTIVE", true, List.of()));
    when(jdbc.query(eq("SELECT queue_id FROM queue_members WHERE user_id=? ORDER BY queue_id"), any(org.springframework.jdbc.core.RowMapper.class), eq(9L))).thenReturn(List.of());
    ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> auth.current(new MockHttpServletRequest("GET", "/api/tickets")));
    assertEquals(403, ex.getStatusCode().value());
    // 修改密码与查询自身信息不受首次改密限制
    assertEquals(9L, auth.current(new MockHttpServletRequest("GET", "/api/auth/me")).id());
  }
}
