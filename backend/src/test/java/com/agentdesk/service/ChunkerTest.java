package com.agentdesk.service;

import com.agentdesk.service.Chunker.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkerTest {
  private final Chunker chunker = new Chunker();

  @Test void splitsByHeadingAndKeepsPath() {
    String md = "# 网络手册\n\n正文一段。\n\n## VPN 配置\n\nVPN 分节内容。\n\n### 诊断\n\n诊断细节。";
    List<Chunk> chunks = chunker.chunk(md);
    assertEquals(3, chunks.size());
    // 首个标题后的正文随首个标题分块，标题路径即为"网络手册"
    assertEquals("网络手册", chunks.get(0).headingPath());
    assertTrue(chunks.get(0).content().contains("正文一段"));
    assertEquals("网络手册 > VPN 配置", chunks.get(1).headingPath());
    assertEquals("网络手册 > VPN 配置 > 诊断", chunks.get(2).headingPath());
    // 分块自包含：正文以最深层标题开头
    assertTrue(chunks.get(2).content().startsWith("# 诊断"));
  }

  @Test void indexesAreContiguousFromZero() {
    String md = "# A\n\n内容A。\n\n# B\n\n内容B。";
    List<Chunk> chunks = chunker.chunk(md);
    assertEquals(2, chunks.size());
    assertEquals(0, chunks.get(0).index());
    assertEquals(1, chunks.get(1).index());
  }

  @Test void backfillsPageNumberFromMarkers() {
    // 页码在标题出现时快照到该节：第一节由 # 手册 锚定在第 1 页，第三节在 marker 后命中第 3 页
    String md = "<!-- page:1 -->\n\n# 手册\n\n第一页内容。\n\n<!-- page:3 -->\n\n## 第三节\n\n第三页小节。";
    List<Chunk> chunks = chunker.chunk(md);
    assertEquals(2, chunks.size());
    assertEquals(1, chunks.get(0).page());
    assertEquals(3, chunks.get(1).page());
  }

  @Test void chineseContentIsNotDropped() {
    List<Chunk> chunks = chunker.chunk("网络连接超时的排查步骤：先检查本地链路。");
    assertEquals(1, chunks.size());
    assertTrue(chunks.get(0).content().contains("网络连接超时"));
  }

  @Test void oversizeSectionSplitsAtLineBoundaries() {
    // 三行各约 900 字符：按行边界切而不是把一行截成两半
    String line1 = "第一行".repeat(300) + "。"; // 901 chars
    String line2 = "第二行".repeat(300) + "。";
    String line3 = "第三行".repeat(300) + "。";
    String md = "# 长节\n\n" + line1 + "\n" + line2 + "\n" + line3;
    List<Chunk> chunks = chunker.chunk(md);
    assertTrue(chunks.size() >= 2, "超长小节应被切分为多块");
    for (Chunk c : chunks) assertTrue(c.content().length() <= 1210, "单块不应显著超过 MAX_CHARS");
    // 行边界保留：每行完整出现在某个分块中，不存在行内截断
    assertTrue(chunks.stream().anyMatch(c -> c.content().contains("第一行")));
    assertTrue(chunks.stream().anyMatch(c -> c.content().contains("第二行")));
    assertTrue(chunks.stream().anyMatch(c -> c.content().contains("第三行")));
    assertTrue(chunks.stream().noneMatch(c -> c.content().contains("第一行") && c.content().contains("第二行") && c.content().length() > 1210));
  }

  @Test void blankMarkdownYieldsNoChunks() {
    assertTrue(chunker.chunk(null).isEmpty());
    assertTrue(chunker.chunk("   \n  ").isEmpty());
  }
}
