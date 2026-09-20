package com.agentdesk.security;

import com.agentdesk.api.ApiModels.AuthUser;
import com.agentdesk.api.ApiModels.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
public class AuthContext {
  public static final String USER_ATTRIBUTE = "agentdesk.auth.user";
  private final JdbcTemplate jdbc;
  private final JwtService jwt;
  public AuthContext(JdbcTemplate jdbc, JwtService jwt) { this.jdbc=jdbc; this.jwt=jwt; }
  public UserContext current(HttpServletRequest request) {
    Object existing=request.getAttribute(USER_ATTRIBUTE); if(existing instanceof UserContext user) return user;
    String token=jwt.readToken(request); if(token==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
    long id=jwt.verify(token); UserContext user=load(id); String path=request.getRequestURI(); if(forcePasswordChange(id)&&!path.equals("/api/auth/me")&&!path.equals("/api/auth/password")) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"首次登录请先修改密码"); request.setAttribute(USER_ATTRIBUTE,user); return user;
  }
  public boolean forcePasswordChange(long id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT force_password_change FROM users WHERE id=?",Boolean.class,id));}
  public UserContext load(long id) {
    AuthUser user=jdbc.queryForObject("SELECT u.id,u.username,u.display_name,r.code,u.department_id,d.name,u.account_status,u.force_password_change FROM users u LEFT JOIN departments d ON d.id=u.department_id LEFT JOIN user_roles ur ON ur.user_id=u.id LEFT JOIN roles r ON r.id=ur.role_id WHERE u.id=?",(rs,n)->new AuthUser(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)==null?"EMPLOYEE":rs.getString(4),(Long)rs.getObject(5),rs.getString(6),rs.getString(7),rs.getBoolean(8),List.of()),id);
    List<Long> queues=jdbc.query("SELECT queue_id FROM queue_members WHERE user_id=? ORDER BY queue_id",(rs,n)->rs.getLong(1),id);
    if(!"ACTIVE".equals(user.accountStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"账号尚未激活");
    return new UserContext(user.id(),user.username(),user.displayName(),user.role(),user.departmentId(),user.departmentName(),queues);
  }

  public void requireAgent(UserContext user) {
    if (!user.agent()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要处理人或管理员权限");
  }

  public void requireAdmin(UserContext user) {
    if (!user.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
  }

}
