package com.agentdesk.service;

import com.agentdesk.api.ApiModels.KnowledgeHit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：融合两路召回（FTS 与向量）。
 * score(d) = Σ 1/(k + rank)，同一分块在两路都命中时得分相加；随后按文章配额去重，保证单篇长文不霸榜。
 * 纯函数实现，便于单测与两处复用（混合检索、浏览页检索）。
 */
public final class RrfMerger {
  private RrfMerger() {}

  public static List<KnowledgeHit> merge(List<KnowledgeHit> primary, List<KnowledgeHit> secondary, int k, int maxPerArticle) {
    int kk = k <= 0 ? 60 : k;
    int quota = Math.max(1, maxPerArticle);
    Map<Long, Double> scores = new LinkedHashMap<>();
    Map<Long, KnowledgeHit> hits = new HashMap<>();
    accumulate(primary, kk, scores, hits);
    accumulate(secondary, kk, scores, hits);
    Map<Long, Integer> perArticle = new HashMap<>();
    List<KnowledgeHit> result = new ArrayList<>();
    scores.entrySet().stream()
        .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
        .map(e -> hits.get(e.getKey()))
        .filter(h -> h != null && h.articleId() > 0)
        .filter(h -> perArticle.merge(h.articleId(), 1, Integer::sum) <= quota)
        .forEach(result::add);
    return result;
  }

  private static void accumulate(List<KnowledgeHit> list, int k, Map<Long, Double> scores, Map<Long, KnowledgeHit> hits) {
    if (list == null) return;
    for (int i = 0; i < list.size(); i++) {
      KnowledgeHit h = list.get(i);
      if (h == null || h.chunkId() == null) continue;
      scores.merge(h.chunkId(), 1.0 / (k + i + 1), Double::sum);
      hits.putIfAbsent(h.chunkId(), h);
    }
  }
}
