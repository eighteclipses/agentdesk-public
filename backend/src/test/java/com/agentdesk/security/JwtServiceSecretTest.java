package com.agentdesk.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** JWT 密钥 fail-fast：不安全或缺失的密钥必须拒绝启动，防止默认密钥可伪造管理员令牌。 */
class JwtServiceSecretTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private static final String SAFE = "0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test void rejectsBlankSecret() {
    assertThrows(IllegalStateException.class, () -> new JwtService("", 28800, mapper));
  }

  @Test void rejectsShortSecret() {
    assertThrows(IllegalStateException.class, () -> new JwtService("short-secret", 28800, mapper));
  }

  @Test void rejectsPlaceholderSecret() {
    assertThrows(IllegalStateException.class, () -> new JwtService("dev-agentdesk-jwt-secret-change-me", 28800, mapper));
  }

  @Test void acceptsStrongSecretAndRoundTripsToken() {
    JwtService jwt = new JwtService(SAFE, 28800, mapper);
    String token = jwt.create(42L);
    assertEquals(42L, jwt.verify(token));
  }

  @Test void rejectsTamperedToken() {
    JwtService jwt = new JwtService(SAFE, 28800, mapper);
    String token = jwt.create(42L);
    String tampered = token.substring(0, token.length() - 4) + "AAAA";
    assertThrows(Exception.class, () -> jwt.verify(tampered));
  }
}
