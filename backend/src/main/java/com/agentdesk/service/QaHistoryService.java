package com.agentdesk.service;

import com.agentdesk.api.ApiModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 问答历史：会话、消息、引用与反馈的持久化，保证每个事实性回答可回溯。 */
@Service
public class QaHistoryService {
  private final JdbcTemplate jdbc; private final ObjectMapper mapper;
  public QaHistoryService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

  public long getOrCreateConversation(long userId, Long conversationId, String title) {
    if (conversationId != null) {
      List<Long> existing = jdbc.query("SELECT user_id FROM conversations WHERE id=?", (rs, n) -> rs.getLong(1), conversationId);
      if (!existing.isEmpty() && existing.get(0) == userId) return conversationId;
    }
    Long id = jdbc.queryForObject("INSERT INTO conversations(user_id,title) VALUES (?,?) RETURNING id", Long.class, userId, title == null ? null : title.substring(0, Math.min(60, title.length())));
    return id;
  }

  public void saveUserMessage(long conversationId, String content, String scope) {
    jdbc.update("INSERT INTO messages(conversation_id,role,content,scope) VALUES (?, 'USER', ?, ?)", conversationId, content, scope);
    touch(conversationId);
  }

  @Transactional
  public long saveAnswer(long conversationId, String answer, boolean ok, String source, double confidence, Integer ttfbMs, Integer totalMs, String scope, List<KnowledgeHit> hits) {
    Long messageId = jdbc.queryForObject("INSERT INTO messages(conversation_id,role,content,scope,ok,source,confidence,ttfb_ms,total_ms) VALUES (?, 'ASSISTANT', ?, ?, ?, ?, ?, ?, ?) RETURNING id",
        Long.class, conversationId, answer, scope, ok, source, confidence, ttfbMs, totalMs);
    if (hits != null) {
      for (KnowledgeHit hit : hits) {
        boolean isNote = hit.citation() != null && hit.citation().startsWith("note:");
        jdbc.update("INSERT INTO citations(message_id,article_id,version_id,chunk_id,note_id,citation,title,snippet,heading_path,page_no,score) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            messageId, isNote ? null : hit.articleId(), isNote ? null : hit.versionId(), hit.chunkId(), isNote ? hit.articleId() : null,
            hit.citation(), hit.title(), hit.snippet(), hit.headingPath(), hit.page(), hit.score());
      }
    }
    touch(conversationId);
    return messageId;
  }

  public void saveFeedback(long userId, FeedbackRequest req) {
    if (req.rating()!=1 && req.rating()!=-1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"评价只能为赞或踩");
    List<Long> owners=jdbc.query("SELECT c.user_id FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE m.id=? AND m.role='ASSISTANT'",(rs,n)->rs.getLong(1),req.messageId());
    if (owners.isEmpty() || owners.get(0)!=userId) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"回答不存在");
    jdbc.update(connection -> {
      var statement=connection.prepareStatement("INSERT INTO knowledge_feedback(message_id,user_id,rating,reasons,comment) VALUES (?,?,?,?,?)");
      statement.setLong(1,req.messageId()); statement.setLong(2,userId); statement.setInt(3,req.rating());
      statement.setArray(4,connection.createArrayOf("text",req.reasons()==null?new String[0]:req.reasons().toArray(new String[0])));
      statement.setString(5,req.comment()); return statement;
    });
  }

  public List<ConversationSummary> conversations(long userId) {
    return jdbc.query("SELECT id,title,updated_at FROM conversations WHERE user_id=? ORDER BY updated_at DESC LIMIT 50", (rs, n) -> new ConversationSummary(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()), userId);
  }

  public List<Map<String, Object>> messages(long userId, long conversationId) {
    List<Long> owner = jdbc.query("SELECT user_id FROM conversations WHERE id=?", (rs, n) -> rs.getLong(1), conversationId);
    if (owner.isEmpty() || owner.get(0) != userId) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,lower(role) AS role,content,scope,ok,source,confidence,created_at FROM messages WHERE conversation_id=? ORDER BY id", conversationId);
    Map<Long, List<Map<String, Object>>> byMessage = new LinkedHashMap<>();
    jdbc.queryForList("SELECT id,message_id,citation,title,snippet,heading_path AS \"headingPath\",page_no AS page,score FROM citations WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?) ORDER BY id", conversationId)
        .forEach(c -> byMessage.computeIfAbsent(((Number) c.get("message_id")).longValue(), k -> new ArrayList<>()).add(c));
    for (Map<String, Object> row : rows) row.put("citations", byMessage.getOrDefault(((Number) row.get("id")).longValue(), List.of()));
    return rows;
  }

  public List<Map<String,Object>> citations(long messageId) {
    return jdbc.queryForList("SELECT id,citation,title,snippet,heading_path AS \"headingPath\",page_no AS page,score FROM citations WHERE message_id=? ORDER BY id",messageId);
  }

  public ConversationDetail conversation(long userId, long conversationId) {
    List<Long> owner = jdbc.query("SELECT user_id FROM conversations WHERE id=?", (rs, n) -> rs.getLong(1), conversationId);
    if (owner.isEmpty() || owner.get(0) != userId) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    String title=jdbc.queryForObject("SELECT title FROM conversations WHERE id=?", String.class, conversationId);
    return new ConversationDetail(conversationId,title,messages(userId,conversationId));
  }

  /** 删除本人会话：引用 → 消息 → 会话逐层清理，反馈记录保留（审计价值）。 */
  @Transactional
  public void deleteConversation(long userId, long conversationId) {
    List<Long> owner = jdbc.query("SELECT user_id FROM conversations WHERE id=?", (rs, n) -> rs.getLong(1), conversationId);
    if (owner.isEmpty() || owner.get(0) != userId) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    jdbc.update("DELETE FROM citations WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?)", conversationId);
    jdbc.update("DELETE FROM messages WHERE conversation_id=?", conversationId);
    jdbc.update("DELETE FROM conversations WHERE id=?", conversationId);
  }

  public void markCitationClicked(long citationId, long userId) {
    List<Long> rows=jdbc.query("SELECT 1 FROM citations c JOIN messages m ON m.id=c.message_id JOIN conversations v ON v.id=m.conversation_id WHERE c.id=? AND v.user_id=?",(rs,n)->rs.getLong(1),citationId,userId);
    if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"引用不存在");
    jdbc.update("UPDATE citations SET clicked=true WHERE id=?",citationId);
  }

  private void touch(long conversationId) { jdbc.update("UPDATE conversations SET updated_at=now() WHERE id=?", conversationId); }
}
