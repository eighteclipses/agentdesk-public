package com.agentdesk.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ApiModels {
  private ApiModels() {}
  public record UserContext(long id, String username, String displayName, String role, Long departmentId, String departmentName, List<Long> queueIds) {
    public boolean admin() { return "ADMIN".equals(role); }
    public boolean agent() { return admin() || "AGENT".equals(role); }
  }
  public record AuthUser(long id, String username, String displayName, String role, Long departmentId, String departmentName, String accountStatus, boolean forcePasswordChange, List<Long> queueIds) {}
  public record TicketCreateRequest(@NotBlank @Size(max=240) String title, @NotBlank @Size(max=20000) String description, String category, String priority) {}
  public record TransitionRequest(@NotBlank String status, @Size(max=20000) String reason, Long assigneeId) {}
  public record CommentRequest(@NotBlank @Size(max=20000) String content) {}
  public record Ticket(long id, long requesterId, Long assigneeId, String title, String description, String category, String priority, String status, Long departmentId, String departmentName, Long queueId, String queueCode, String queueName, Instant createdAt, Instant updatedAt, Instant dueAt, String requesterName, String assigneeName) {}
  public record Comment(long id, long ticketId, long authorId, String authorName, String content, Instant createdAt) {}
  public record Attachment(long id, long ticketId, String fileName, String contentType, long size, String objectKey, Instant createdAt) {}
  public record KnowledgeCreateRequest(@NotBlank String title, @NotBlank String content, String category, List<String> tags, String visibility, List<Long> departmentIds, String sensitivity) {}
  public record KnowledgeUpdateRequest(String title, String content, String category, String visibility, String sensitivity, List<String> tags, List<Long> departmentIds) {}
  public record KnowledgeArticle(long id, String title, String category, String status, int version, String visibility, Instant updatedAt) {}
  /** chunkId/chunkIndex/headingPath/page 仅分块命中时非空；citation 形如 article:1/version:1#c3 或 article:1/version:1、note:12。 */
  public record KnowledgeHit(long articleId, int versionId, String title, String snippet, double score, String citation, Long chunkId, Integer chunkIndex, String headingPath, Integer page) {
    public KnowledgeHit(long articleId, int versionId, String title, String snippet, double score, String citation) { this(articleId, versionId, title, snippet, score, citation, null, null, null, null); }
  }
  public record ClassifyRequest(Long ticketId, @NotBlank String title, @NotBlank String description, String provider) {}
  public record DraftRequest(Long ticketId, @NotBlank String question, String provider) {}
  public record RecommendRequest(Long ticketId, @NotBlank String title, @NotBlank String description, String provider) {}
  public record KnowledgeAskRequest(@NotBlank String question, String provider, String scope, Long conversationId) {}
  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
  public record RegisterRequest(@NotBlank String username, @NotBlank String password, @NotBlank String displayName, @NotNull Long departmentId) {}
  public record UserCreateRequest(@NotBlank String username, @NotBlank String password, @NotBlank String displayName, @NotNull Long departmentId, @NotBlank String role) {}
  public record UserUpdateRequest(Long departmentId, String role, Boolean active, String accountStatus) {}
  public record QueueRequest(@NotBlank String code, @NotBlank String name, String description, Boolean active) {}
  public record QueueMemberRequest(@NotNull Long userId) {}
  public record DepartmentRequest(@NotBlank String name) {}
  public record ResetPasswordRequest(@NotBlank String password) {}
  public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
  public record KnowledgeAskResponse(boolean ok, String answer, double confidence, List<KnowledgeHit> citations, String failureReason, String source) {}
  public record AgentActionRequest(@NotBlank String actionType, @NotNull Long ticketId, Map<String,Object> payload) {}
  public record AgentAction(long id, String actionType, long ticketId, String status, Map<String,Object> payload, Instant createdAt, Instant decidedAt) {}
  /** 队列内可指派的处理人 */
  public record AssigneeOption(long id, String username, String displayName) {}
  public record Page<T>(List<T> items, long total) {}

  // ---- 导入流水线 ----
  public record ImportItem(long id, long batchId, String fileName, String contentType, long size, String parser, String status, int progress, String error, String summary, Long articleId, Long duplicateOf, String sourcePath, Instant createdAt, Instant updatedAt) {}
  public record ImportBatch(long id, String name, String status, int totalItems, Instant createdAt, List<ImportItem> items) {}

  // ---- 问答历史与反馈 ----
  public record FeedbackRequest(@NotNull Long messageId, int rating, List<String> reasons, String comment) {}
  public record ConversationSummary(long id, String title, Instant updatedAt) {}
  public record ConversationDetail(long id, String title, List<Map<String,Object>> messages) {}

  // ---- 个人 Vault ----
  public record NoteUpsertRequest(String title, String content, String folderPath, List<String> tags) {}
  public record Note(long id, String title, String folderPath, String content, List<String> tags, String status, int version, Instant updatedAt) {}
  public record GraphNode(long id, String type, String label, String folder) {}
  public record GraphEdge(long source, long target, String kind, boolean resolved) {}

  // ---- 知识详情 ----
  public record ArticleVersionInfo(long id, int version, String status, boolean chunkReady, boolean current, long characters, String author, Instant createdAt) {}
  public record ChunkInfo(long id, int chunkIndex, String headingPath, String content, Integer pageNo, Integer charCount) {}

  // ---- 导入批次 ----
  public record ImportBatchSummary(long id, String name, String status, int totalItems, Instant createdAt, long queued, long active, long review, long published, long failed, long duplicate) {}
  public record BatchCreateResult(long batchId, int totalItems, List<Long> itemIds) {}
  public record ReviewRequest(boolean approve, String comment) {}

  // ---- 站内通知 ----
  public record NotificationItem(long id, String type, String title, String body, String link, String refType, Long refId, boolean read, Instant createdAt) {}
  public record NotificationPage(List<NotificationItem> items, long total, long unread) {}

  // ---- 数据统计 ----
  public record StatBucket(String label, long count) {}
  public record DayPoint(String date, long count) {}
}
