package com.agentdesk.config;

import com.agentdesk.security.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 进程内限流：登录防暴力破解；知识问答控制 LLM 调用成本。 */
@Configuration
public class RateLimitConfig {
  /** 同一来源每分钟最多 5 次登录尝试，允许少量突发 */
  @Bean public RateLimiter loginRateLimiter() { return new RateLimiter(5, 5.0 / 60); }

  /** 每个登录用户每分钟最多 20 次问答 */
  @Bean public RateLimiter askRateLimiter() { return new RateLimiter(20, 20.0 / 60); }
}
