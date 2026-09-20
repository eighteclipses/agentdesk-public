package com.agentdesk.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

  @Test void allowsWithinCapacity() {
    RateLimiter limiter = new RateLimiter(3, 1);
    assertTrue(limiter.tryAcquire("a"));
    assertTrue(limiter.tryAcquire("a"));
    assertTrue(limiter.tryAcquire("a"));
  }

  @Test void rejectsAfterCapacityExhausted() {
    RateLimiter limiter = new RateLimiter(2, 0.001); // 极低恢复速率
    assertTrue(limiter.tryAcquire("key"));
    assertTrue(limiter.tryAcquire("key"));
    assertFalse(limiter.tryAcquire("key"));
  }

  @Test void independentKeysDoNotInterfere() {
    RateLimiter limiter = new RateLimiter(1, 0.001);
    assertTrue(limiter.tryAcquire("user1"));
    assertFalse(limiter.tryAcquire("user1"));
    assertTrue(limiter.tryAcquire("user2")); // 不同 key 独立计数
  }

  @Test void refillsOverTime() throws InterruptedException {
    RateLimiter limiter = new RateLimiter(1, 100); // 100/s 恢复
    assertTrue(limiter.tryAcquire("x"));
    assertFalse(limiter.tryAcquire("x"));
    Thread.sleep(20); // 20ms * 100/s = 2 tokens 恢复
    assertTrue(limiter.tryAcquire("x"));
  }
}
