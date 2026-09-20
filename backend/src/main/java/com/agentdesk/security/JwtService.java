package com.agentdesk.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Service
public class JwtService {
  private final String secret; private final long ttlSeconds; private final ObjectMapper mapper;
  public JwtService(@Value("${security.jwt.secret:}") String secret,@Value("${security.jwt.ttl-seconds:28800}") long ttlSeconds,ObjectMapper mapper){
    if(secret==null||secret.isBlank()||secret.length()<32||secret.contains("change-me")||secret.contains("change_me"))
      throw new IllegalStateException("security.jwt.secret 未配置或不安全：请设置至少 32 位的随机 JWT_SECRET（例如 openssl rand -hex 32），服务拒绝以默认密钥启动");
    this.secret=secret;this.ttlSeconds=ttlSeconds;this.mapper=mapper;}
  public String create(long userId){try{String h=encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"),p=encode(mapper.writeValueAsString(Map.of("sub",userId,"exp",Instant.now().getEpochSecond()+ttlSeconds)));return h+"."+p+"."+sign(h+"."+p);}catch(Exception e){throw new IllegalStateException("JWT 创建失败",e);}}
  public long verify(String token){try{String[] p=token.split("\\.");if(p.length!=3||!java.security.MessageDigest.isEqual(p[2].getBytes(StandardCharsets.UTF_8),sign(p[0]+"."+p[1]).getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();Map<?,?> body=mapper.readValue(new String(Base64.getUrlDecoder().decode(p[1]),StandardCharsets.UTF_8),Map.class);if(((Number)body.get("exp")).longValue()<Instant.now().getEpochSecond())throw new IllegalArgumentException();return ((Number)body.get("sub")).longValue();}catch(Exception e){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"登录已失效");}}
  public String readToken(HttpServletRequest req){if(req.getCookies()!=null)for(Cookie c:req.getCookies())if("agentdesk_token".equals(c.getName()))return c.getValue();String a=req.getHeader("Authorization");return a!=null&&a.startsWith("Bearer ")?a.substring(7):null;}
  private String encode(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
  private String sign(String s)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(s.getBytes(StandardCharsets.UTF_8)));}
}
