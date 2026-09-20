package com.agentdesk.controller;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import com.agentdesk.security.JwtService;
import com.agentdesk.security.PasswordService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final JdbcTemplate jdbc; private final PasswordService passwords; private final JwtService jwt; private final AuthContext auth;
  private final com.agentdesk.security.RateLimiter loginRateLimiter;
  @org.springframework.beans.factory.annotation.Value("${security.cookie.secure:}") private String cookieSecure;
  public AuthController(JdbcTemplate jdbc,PasswordService passwords,JwtService jwt,AuthContext auth,@org.springframework.beans.factory.annotation.Qualifier("loginRateLimiter") com.agentdesk.security.RateLimiter loginRateLimiter){this.jdbc=jdbc;this.passwords=passwords;this.jwt=jwt;this.auth=auth;this.loginRateLimiter=loginRateLimiter;}
  @PostMapping("/login") public AuthUser login(@Valid @RequestBody LoginRequest body,HttpServletRequest request,HttpServletResponse response){
    String source=Objects.toString(request.getHeader("X-Forwarded-For"),"").split(",")[0].trim(); if(source.isEmpty()) source=request.getRemoteAddr();
    if(!loginRateLimiter.tryAcquire(source)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"登录尝试过于频繁，请稍后再试");
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,password_hash,account_status FROM users WHERE username=?",body.username());
    if(rows.isEmpty()||!passwords.matches(body.password(),(String)rows.get(0).get("password_hash"))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"用户名或密码错误");
    Map<String,Object> row=rows.get(0); if(!"ACTIVE".equals(row.get("account_status"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"账号正在等待管理员审核或已停用");
    Cookie cookie=new Cookie("agentdesk_token",jwt.create(((Number)row.get("id")).longValue())); cookie.setHttpOnly(true);
    // HTTPS 请求自动置 Secure；可用 security.cookie.secure=true/false 强制（反向代理终结 TLS 时建议显式 true）
    cookie.setSecure(cookieSecure.isBlank()?("https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))||request.isSecure()):Boolean.parseBoolean(cookieSecure));
    cookie.setAttribute("SameSite","Lax"); cookie.setPath("/"); cookie.setMaxAge(28800); response.addCookie(cookie);
    return user(((Number)row.get("id")).longValue());
  }
  @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> register(@Valid @RequestBody RegisterRequest body){
    if(body.password().length()<8) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"密码至少 8 位");
    if(jdbc.queryForObject("SELECT count(*) FROM departments WHERE id=?",Integer.class,body.departmentId())==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"部门不存在");
    if(jdbc.queryForObject("SELECT count(*) FROM users WHERE username=?",Integer.class,body.username())>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"用户名已存在");
    Long id=jdbc.queryForObject("INSERT INTO users(username,display_name,department_id,password_hash,account_status,force_password_change) VALUES (?,?,?,?,'PENDING_APPROVAL',true) RETURNING id",Long.class,body.username(),body.displayName(),body.departmentId(),passwords.hash(body.password()));
    jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='EMPLOYEE'",id);
    return Map.of("id",id,"username",body.username(),"accountStatus","PENDING_APPROVAL","message","注册成功，请等待管理员审核");
  }
  @PostMapping("/logout") public Map<String,String> logout(HttpServletResponse response){Cookie c=new Cookie("agentdesk_token","");c.setHttpOnly(true);c.setPath("/");c.setMaxAge(0);response.addCookie(c);return Map.of("message","已退出登录");}
  @GetMapping("/me") public AuthUser me(HttpServletRequest request){return user(auth.current(request).id());}
  @PostMapping("/password") public Map<String,Object> changePassword(@Valid @RequestBody ChangePasswordRequest body,HttpServletRequest request){long id=auth.current(request).id();String stored=jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,id);if(!passwords.matches(body.currentPassword(),stored))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"当前密码错误");if(body.newPassword().length()<8)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"新密码至少 8 位");jdbc.update("UPDATE users SET password_hash=?,force_password_change=false WHERE id=?",passwords.hash(body.newPassword()),id);return Map.of("message","密码已更新");}
  @GetMapping("/departments") public List<Map<String,Object>> departments(){return jdbc.queryForList("SELECT id,name FROM departments ORDER BY id");}
  public AuthUser user(long id){
    Map<String,Object> row=jdbc.queryForMap("SELECT u.id,u.username,u.display_name,r.code,u.department_id,d.name,u.account_status,u.force_password_change FROM users u LEFT JOIN departments d ON d.id=u.department_id LEFT JOIN user_roles ur ON ur.user_id=u.id LEFT JOIN roles r ON r.id=ur.role_id WHERE u.id=?",id);
    List<Long> queues=jdbc.query("SELECT queue_id FROM queue_members WHERE user_id=? ORDER BY queue_id",(rs,n)->rs.getLong(1),id);
    return new AuthUser(((Number)row.get("id")).longValue(),(String)row.get("username"),(String)row.get("display_name"),(String)row.getOrDefault("code","EMPLOYEE"),(Long)row.get("department_id"),(String)row.get("name"),(String)row.get("account_status"),Boolean.TRUE.equals(row.get("force_password_change")),queues);
  }
}
