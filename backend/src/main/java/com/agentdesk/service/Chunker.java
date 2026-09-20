package com.agentdesk.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 结构化分块：按标题层级切片，保留标题路径；PDF 解析产物中的 {@code <!-- page:N -->}
 * 标记用于回填页码。分块是检索与可点击引用（article:x/version:y#cN）的基本单位。
 */
@Service
public class Chunker {
  public record Chunk(int index, String headingPath, String content, Integer page) {}

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
  private static final Pattern PAGE_MARKER = Pattern.compile("<!--\\s*page:(\\d+)\\s*-->");
  private static final int MAX_CHARS = 1200;

  public List<Chunk> chunk(String markdown) {
    List<Chunk> result = new ArrayList<>();
    if (markdown == null || markdown.isBlank()) return result;
    List<String> headings = new ArrayList<>();
    StringBuilder section = new StringBuilder();
    Integer page = null;
    Integer sectionPage = null;
    for (String rawLine : markdown.split("\r?\n", -1)) {
      Matcher marker = PAGE_MARKER.matcher(rawLine);
      if (marker.find()) { page = Integer.parseInt(marker.group(1)); continue; }
      Matcher heading = HEADING.matcher(rawLine.strip());
      if (heading.matches()) {
        emit(result, headings, section, sectionPage);
        int level = heading.group(1).length();
        while (headings.size() >= level) headings.remove(headings.size() - 1);
        // 填充到 level 个元素再写入第 level-1 位：首篇文档首个标题时列表为空，补到 level-1 会导致 set 越界
        while (headings.size() < level) headings.add("");
        headings.set(level - 1, heading.group(2).strip());
        section.setLength(0);
        sectionPage = page;
      } else {
        section.append(rawLine).append('\n');
      }
    }
    emit(result, headings, section, sectionPage);
    List<Chunk> indexed = new ArrayList<>();
    for (Chunk c : result) indexed.add(new Chunk(indexed.size(), c.headingPath(), c.content(), c.page()));
    return indexed;
  }

  private void emit(List<Chunk> out, List<String> headings, StringBuilder section, Integer page) {
    String text = section.toString().strip();
    if (text.length() < 4 && headings.stream().allMatch(String::isBlank)) return;
    String path = headings.stream().filter(h -> !h.isBlank()).reduce((a, b) -> a + " > " + b).orElse("");
    String deepest = headings.stream().filter(h -> !h.isBlank()).reduce((a, b) -> b).orElse("");
    String body = (deepest.isBlank() ? "" : "# " + deepest + "\n\n") + text;
    if (body.isBlank()) return;
    for (String part : splitLong(body)) out.add(new Chunk(0, path, part, page));
  }

  private List<String> splitLong(String body) {
    List<String> parts = new ArrayList<>();
    if (body.length() <= MAX_CHARS) { parts.add(body); return parts; }
    StringBuilder current = new StringBuilder();
    for (String paragraph : body.split("\n\\s*\n")) {
      if (current.length() + paragraph.length() + 2 > MAX_CHARS && current.length() > 0) {
        parts.add(current.toString().strip());
        current.setLength(0);
      }
      // 单段超长时优先按行边界切，行仍超限（如无换行的长表格/日志）才按字符截断
      List<String> pieces = new ArrayList<>();
      StringBuilder piece = new StringBuilder();
      for (String line : paragraph.split("\n", -1)) {
        if (piece.length() + line.length() + 1 > MAX_CHARS && piece.length() > 0) {
          pieces.add(piece.toString());
          piece.setLength(0);
        }
        if (line.length() > MAX_CHARS) {
          if (!piece.isEmpty()) { pieces.add(piece.toString()); piece.setLength(0); }
          for (int i = 0; i < line.length(); i += MAX_CHARS)
            pieces.add(line.substring(i, Math.min(i + MAX_CHARS, line.length())));
        } else {
          piece.append(line).append('\n');
        }
      }
      if (!piece.isEmpty()) pieces.add(piece.toString());
      for (String p : pieces) {
        if (current.length() + p.length() + 2 > MAX_CHARS && current.length() > 0) {
          parts.add(current.toString().strip());
          current.setLength(0);
        }
        current.append(p).append("\n\n");
      }
    }
    if (!current.isEmpty()) parts.add(current.toString().strip());
    return parts;
  }
}
