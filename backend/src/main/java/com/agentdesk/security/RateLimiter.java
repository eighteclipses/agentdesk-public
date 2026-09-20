package com.agentdesk.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内令牌桶限流器：用于登录防暴力破解与 LLM 问答成本控制。
 * 单实例部署足够；多实例部署时应替换为基于 Redis 的分布式限流。
 */
public class RateLimiter {
  private static final int MAX_BUCKETS = 10_000;
  private static final int TRIM_TARGET = 8_000;
  private final double capacity; private final double refillPerSecond;
  private final Map<String, double[]> buckets = new ConcurrentHashMap<>();

  /** capacity：桶容量（突发上限）；refillPerSecond：每秒恢复的令牌数 */
  public RateLimiter(double capacity, double refillPerSecond) {
    this.capacity = capacity; this.refillPerSecond = refillPerSecond;
  }

  public boolean tryAcquire(String key) {
    long now = System.nanoTime();
    synchronized (this) {
      if (buckets.size() > MAX_BUCKETS) evict(now);
      double[] bucket = buckets.computeIfAbsent(key, k -> new double[]{capacity, now});
      double elapsedSeconds = Math.max(0, now - bucket[1]) / 1_000_000_000d;
      bucket[0] = Math.min(capacity, bucket[0] + elapsedSeconds * refillPerSecond);
      bucket[1] = now;
      if (bucket[0] >= 1) { bucket[0] -= 1; return true; }
      return false;
    }
  }

  /**
   * 容量防膨胀（摊销）：先淘汰已完全恢复（闲置超过一个补充周期）的桶——语义上与新建桶无差别；
   * 仍超限时按最久未使用裁剪到 TRIM_TARGET。绝不整体清空，避免攻击者用海量假 key 重置在线用户的限流状态；
   * 裁剪到水位线以下再放行，下一次淘汰要再积累数千个新 key 才触发，均摊成本可控。
   */
  private void evict(long now) {
    double staleNanos = Math.max(60d, capacity / Math.max(refillPerSecond, 1e-9)) * 1_000_000_000d;
    buckets.values().removeIf(b -> now - b[1] > staleNanos);
    if (buckets.size() <= MAX_BUCKETS) return;
    int toRemove = buckets.size() - TRIM_TARGET;
    if (toRemove <= 0) return;
    buckets.entrySet().stream()
        .sorted((a, b) -> Double.compare(a.getValue()[1], b.getValue()[1]))
        .limit(toRemove)
        .map(java.util.Map.Entry::getKey)
        .toList()
        .forEach(buckets::remove);
  }
}
