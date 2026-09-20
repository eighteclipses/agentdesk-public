package com.agentdesk.service;

import com.agentdesk.api.ApiModels.KnowledgeHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RrfMergerTest {

  private KnowledgeHit hit(long articleId, long chunkId, String title) {
    return new KnowledgeHit(articleId, 1, title, "片段", 0.0, "article:" + articleId + "/version:1#c0", chunkId, 0, null, null);
  }

  @Test void ftsFirstVectorSecondScoresAddUp() {
    // 同一分块在两路都命中：RRF 得分相加后应排在只命中一路的分块前面
    KnowledgeHit dual = hit(1, 100, "双路命中");
    KnowledgeHit ftsOnly = hit(2, 200, "仅FTS");
    KnowledgeHit vecOnly = hit(3, 300, "仅向量");
    List<KnowledgeHit> merged = RrfMerger.merge(List.of(dual, ftsOnly), List.of(vecOnly, dual), 60, 2);
    assertEquals(3, merged.size());
    assertEquals(100L, merged.get(0).chunkId());
  }

  @Test void primaryListOrderBeatsSecondaryOnTie() {
    // FTS 第 1 名与向量第 1 名得分并列（各 1/(k+1)）；并列时按插入顺序稳定排序，FTS 侧在前
    List<KnowledgeHit> merged = RrfMerger.merge(List.of(hit(1, 10, "A"), hit(2, 20, "B")), List.of(hit(3, 30, "C")), 60, 5);
    assertEquals(10L, merged.get(0).chunkId());
    assertEquals(30L, merged.get(1).chunkId());
    assertEquals(20L, merged.get(2).chunkId());
  }

  @Test void perArticleQuotaDropsLongArticleTails() {
    // 文章 1 的 3 个分块占据 FTS 前三：配额=2 时第三块让位给文章 2
    List<KnowledgeHit> fts = List.of(hit(1, 11, "A1"), hit(1, 12, "A2"), hit(1, 13, "A3"), hit(2, 21, "B1"));
    List<KnowledgeHit> merged = RrfMerger.merge(fts, List.of(), 60, 2);
    assertEquals(3, merged.size());
    assertEquals(21L, merged.get(2).chunkId());
    assertTrue(merged.stream().filter(h -> h.articleId() == 1).count() <= 2);
  }

  @Test void emptySecondaryIsAllowed() {
    List<KnowledgeHit> merged = RrfMerger.merge(List.of(hit(1, 1, "A")), List.of(), 60, 2);
    assertEquals(1, merged.size());
    assertTrue(RrfMerger.merge(List.of(), List.of(), 60, 2).isEmpty());
  }
}
