package com.agentdesk.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 淘汰策略语义：超过容量上限时按最久未使用裁剪，而不是整体清空——
 * 最近的活跃 key 限流状态必须保留（否则攻击者可用海量假 key 重置他人的防暴力破解计数）。
 */
class RateLimiterEvictTest {

  @Test void exhaustedActiveBucketSurvivesFlood() {
    RateLimiter limiter = new RateLimiter(2, 0.001); // 一个补充周期 ~2000s，测试期间不会有桶过期
    for (int i = 0; i < 10_500; i++) limiter.tryAcquire("flood-a-" + i); // 触发第一轮淘汰
    assertTrue(limiter.tryAcquire("active-1"));
    assertTrue(limiter.tryAcquire("active-1"));
    assertFalse(limiter.tryAcquire("active-1")); // 令牌耗尽
    for (int i = 0; i < 2_500; i++) limiter.tryAcquire("flood-b-" + i); // 再次越过上限触发淘汰
    assertFalse(limiter.tryAcquire("active-1")); // active-1 最近使用，未被淘汰重置
  }

  @Test void newKeysStillWorkAfterEviction() {
    RateLimiter limiter = new RateLimiter(1, 0.001);
    for (int i = 0; i < 12_000; i++) limiter.tryAcquire("k" + i);
    assertTrue(limiter.tryAcquire("brand-new")); // 淘汰后新 key 正常获取令牌
  }

  @Test void capacityExhaustionStillEnforcedAfterFlood() {
    RateLimiter limiter = new RateLimiter(1, 0.001);
    for (int i = 0; i < 12_000; i++) limiter.tryAcquire("noise-" + i);
    assertTrue(limiter.tryAcquire("victim"));
    assertFalse(limiter.tryAcquire("victim")); // 桶容量语义不受淘汰影响
  }
}
