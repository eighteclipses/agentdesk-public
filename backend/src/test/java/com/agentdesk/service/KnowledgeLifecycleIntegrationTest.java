package com.agentdesk.service;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/**
 * Lifecycle checks against a real PostgreSQL database.
 *
 * <p>The suite is deliberately opt-in. It migrates an isolated agentdesk_test
 * database and rolls back each test's data transaction, so it never cleans or
 * truncates a database that may contain user data.</p>
 */
@EnabledIfEnvironmentVariable(named = "AGENTDESK_TEST_JDBC", matches = ".+")
class KnowledgeLifecycleIntegrationTest {
  private static JdbcTemplate jdbc;
  private static TransactionTemplate tx;
  private static TransactionTemplate nested;
  private static KnowledgeService service;

  private static final AuthContext auth = mock(AuthContext.class);
  private static final AuditService audit = mock(AuditService.class);
  private static final NotificationService notifications = mock(NotificationService.class);
  private static final EmbeddingService embeddings = mock(EmbeddingService.class);

  private record Fixture(long departmentId, long adminId, long employeeId, long articleId,
                         long publishedVersionId, long publishedImportId) {}

  @BeforeAll
  static void migrateIsolatedDatabase() {
    String url = requiredEnvironment("AGENTDESK_TEST_JDBC");
    if (!url.startsWith("jdbc:postgresql://") || !"/agentdesk_test".equals(java.net.URI.create(url.substring(5)).getPath())) {
      throw new IllegalStateException("Refusing to run knowledge lifecycle tests unless AGENTDESK_TEST_JDBC contains agentdesk_test");
    }
    String username = requiredEnvironment("AGENTDESK_TEST_USER");
    String password = requiredEnvironment("AGENTDESK_TEST_PASSWORD");

    DataSource dataSource = new DriverManagerDataSource(url, username, password);
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate();

    jdbc = new JdbcTemplate(dataSource);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    transactionManager.setNestedTransactionAllowed(true);
    tx = new TransactionTemplate(transactionManager);
    nested = new TransactionTemplate(transactionManager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);

    doNothing().when(auth).requireAdmin(any());
    service = new KnowledgeService(jdbc, auth, audit, new Chunker(), notifications, embeddings, transactionManager);
  }

  @Test
  void employeeSeesOnlyPublishedVersionAndCannotReadDraftHistoryOrTheirFiles() {
    rollbackAfter(() -> {
      Fixture f = fixture("Online visibility");
      long draft = addVersion(f, 2, "DRAFT", "draft secret text", "object://draft-only");

      Map<String, Object> current = service.detail(f.articleId(), employee(f), null);
      assertEquals(f.publishedVersionId(), number(current.get("versionId")));
      assertEquals("published text", current.get("content"));

      List<ArticleVersionInfo> versions = service.versions(f.articleId(), employee(f));
      assertEquals(List.of(f.publishedVersionId()), versions.stream().map(ArticleVersionInfo::id).toList());

      ResponseStatusException historyError = assertThrows(ResponseStatusException.class,
          () -> service.detail(f.articleId(), employee(f), draft));
      assertEquals(404, historyError.getStatusCode().value());

      ResponseStatusException fileError = assertThrows(ResponseStatusException.class,
          () -> service.fileSource(f.articleId(), employee(f), draft));
      assertEquals(404, fileError.getStatusCode().value());

      Map<String, Object> file = service.fileSource(f.articleId(), employee(f), null);
      assertEquals("object://published-v1", file.get("objectKey"));
      assertEquals("published-v1.pdf", file.get("fileName"));
    });
  }

  @Test
  void administratorCanReadDraftAndHistoryWithExactVersionFiles() {
    rollbackAfter(() -> {
      Fixture f = fixture("Administrator history");
      long draft = addVersion(f, 2, "DRAFT", "draft administrator text", "object://draft-v2");

      Map<String, Object> history = service.detail(f.articleId(), admin(f), draft);
      assertEquals(draft, number(history.get("versionId")));
      assertEquals("draft administrator text", history.get("content"));
      assertEquals("version-2.pdf", mapValue(history, "file", "fileName"));

      Map<String, Object> oldFile = service.fileSource(f.articleId(), admin(f), f.publishedVersionId());
      assertEquals("object://published-v1", oldFile.get("objectKey"));

      List<ArticleVersionInfo> versions = service.versions(f.articleId(), admin(f));
      assertEquals(List.of(draft, f.publishedVersionId()), versions.stream().map(ArticleVersionInfo::id).toList());
      assertEquals("DRAFT", versions.get(0).status());
    });
  }

  @Test
  void latestImportDoesNotReplaceThePublishedVersionOriginal() {
    rollbackAfter(() -> {
      Fixture f = fixture("Exact import source");
      long latestImport = addImportItem(f, "latest-upload.pdf", "object://latest-upload");
      jdbc.update("UPDATE import_items SET status='REVIEW' WHERE id=?", latestImport);

      Map<String, Object> employeeFile = service.fileSource(f.articleId(), employee(f), null);
      assertEquals("object://published-v1", employeeFile.get("objectKey"));
      assertNotEquals("object://latest-upload", employeeFile.get("objectKey"));

      Map<String, Object> adminFile = service.fileSource(f.articleId(), admin(f), f.publishedVersionId());
      assertEquals("object://published-v1", adminFile.get("objectKey"));
    });
  }

  @Test
  void rejectedEditKeepsOldPublishedVersionOnline() {
    rollbackAfter(() -> {
      Fixture f = fixture("Reject edit");
      Map<String, Object> update = service.update(f.articleId(),
          new KnowledgeUpdateRequest("Updated title", "edited draft text", null, null, null, List.of(), List.of()), admin(f));
      long draft = number(update.get("newVersionId"));

      assertArticleState(f.articleId(), "PUBLISHED", f.publishedVersionId());
      assertEquals("edited draft text", jdbc.queryForObject("SELECT content FROM knowledge_versions WHERE id=?", String.class, draft));
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE version_id=?", Integer.class, draft));
      assertEquals(draft, number(service.list(admin(f)).stream().filter(a -> a.id() == f.articleId()).findFirst().orElseThrow().pendingVersionId()));

      Map<String, Object> employeeBefore = service.detail(f.articleId(), employee(f), null);
      assertEquals("published text", employeeBefore.get("content"));

      service.review(f.articleId(), false, "请补充依据", admin(f), draft);

      assertArticleState(f.articleId(), "PUBLISHED", f.publishedVersionId());
      assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM knowledge_versions WHERE id=?", String.class, f.publishedVersionId()));
      assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM knowledge_versions WHERE id=?", String.class, draft));
      Map<String, Object> employeeAfter = service.detail(f.articleId(), employee(f), null);
      assertEquals(f.publishedVersionId(), number(employeeAfter.get("versionId")));
      assertEquals("published text", employeeAfter.get("content"));
    });
  }

  @Test
  void approvedEditPublishesDraftAndSwitchesCurrentVersion() {
    rollbackAfter(() -> {
      Fixture f = fixture("Approve edit");
      Map<String, Object> update = service.update(f.articleId(),
          new KnowledgeUpdateRequest("Approved title", "approved draft text", null, null, null, List.of(), List.of()), admin(f));
      long draft = number(update.get("newVersionId"));

      service.review(f.articleId(), true, "已核对", admin(f), draft);

      assertArticleState(f.articleId(), "PUBLISHED", draft);
      assertEquals("ARCHIVED", jdbc.queryForObject("SELECT status FROM knowledge_versions WHERE id=?", String.class, f.publishedVersionId()));
      assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM knowledge_versions WHERE id=?", String.class, draft));

      Map<String, Object> employee = service.detail(f.articleId(), employee(f), null);
      assertEquals(draft, number(employee.get("versionId")));
      assertEquals("approved draft text", employee.get("content"));
      assertEquals(List.of(draft), service.versions(f.articleId(), employee(f)).stream().map(ArticleVersionInfo::id).toList());
      assertThrows(ResponseStatusException.class, () -> service.fileSource(f.articleId(), employee(f), null));
      ResponseStatusException oldDetailError = assertThrows(ResponseStatusException.class,
          () -> service.detail(f.articleId(), employee(f), f.publishedVersionId()));
      assertEquals(404, oldDetailError.getStatusCode().value());
      ResponseStatusException oldFileError = assertThrows(ResponseStatusException.class,
          () -> service.fileSource(f.articleId(), employee(f), f.publishedVersionId()));
      assertEquals(404, oldFileError.getStatusCode().value());
    });
  }

  @Test
  void approvingNewestDraftArchivesOlderDraftAndCancelsOnlyItsReviewImport() {
    rollbackAfter(() -> {
      Fixture f = fixture("Multiple draft imports");
      long oldDraft = addVersion(f, 2, "DRAFT", "older imported draft", "object://older-draft");
      long newestDraft = addVersion(f, 3, "DRAFT", "newest imported draft", "object://newest-draft");
      long oldImport = jdbc.queryForObject("SELECT source_import_item_id FROM knowledge_versions WHERE id=?", Long.class, oldDraft);
      long newestImport = jdbc.queryForObject("SELECT source_import_item_id FROM knowledge_versions WHERE id=?", Long.class, newestDraft);

      service.review(f.articleId(), true, "发布最新版本", admin(f), newestDraft, "PUBLISHED");

      assertArticleState(f.articleId(), "PUBLISHED", newestDraft);
      assertEquals("ARCHIVED", jdbc.queryForObject("SELECT status FROM knowledge_versions WHERE id=?", String.class, oldDraft));
      assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM knowledge_versions WHERE id=?", String.class, newestDraft));
      assertEquals("CANCELED", jdbc.queryForObject("SELECT status FROM import_items WHERE id=?", String.class, oldImport));
      assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM import_items WHERE id=?", String.class, newestImport));
      assertEquals(newestDraft, jdbc.queryForObject("SELECT current_version_id FROM knowledge_articles WHERE id=?", Long.class, f.articleId()));
    });
  }

  @Test
  void retractedArticleRequiresFreshExpectedStatusBeforeExplicitRepublish() {
    rollbackAfter(() -> {
      Fixture f = fixture("Republish after retract");
      service.retract(f.articleId(), admin(f));

      ResponseStatusException staleStatus = assertThrows(ResponseStatusException.class,
          () -> service.review(f.articleId(), true, "旧页面审批", admin(f), f.publishedVersionId(), "PUBLISHED"));
      assertEquals(409, staleStatus.getStatusCode().value());
      assertEquals("ARCHIVED", jdbc.queryForObject("SELECT status FROM knowledge_articles WHERE id=?", String.class, f.articleId()));
      assertEquals(f.publishedVersionId(), jdbc.queryForObject("SELECT current_version_id FROM knowledge_articles WHERE id=?", Long.class, f.articleId()));
      assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM knowledge_versions WHERE id=?", String.class, f.publishedVersionId()));

      service.review(f.articleId(), true, "确认重新发布", admin(f), f.publishedVersionId(), "ARCHIVED");

      assertArticleState(f.articleId(), "PUBLISHED", f.publishedVersionId());
      assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM knowledge_versions WHERE id=?", String.class, f.publishedVersionId()));
      assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM import_items WHERE id=?", String.class, f.publishedImportId()));
    });
  }

  @Test
  void staleExpectedVersionCannotApproveANewerDraft() {
    rollbackAfter(() -> {
      Fixture f = fixture("Stale review");
      long firstDraft = addVersion(f, 2, "DRAFT", "first draft", null);
      long newerDraft = addVersion(f, 3, "DRAFT", "newer draft", null);

      ResponseStatusException error = assertThrows(ResponseStatusException.class,
          () -> service.review(f.articleId(), true, "过期审核", admin(f), firstDraft));
      assertEquals(409, error.getStatusCode().value());
      assertArticleState(f.articleId(), "PUBLISHED", f.publishedVersionId());
      assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM knowledge_versions WHERE id=?", String.class, firstDraft));
      assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM knowledge_versions WHERE id=?", String.class, newerDraft));
    });
  }

  @Test
  void invalidDepartmentForeignKeyRollsBackDraftChunksAndMetadataTogether() {
    rollbackAfter(() -> {
      Fixture f = fixture("Atomic edit");
      int beforeVersions = jdbc.queryForObject("SELECT count(*) FROM knowledge_versions WHERE article_id=?", Integer.class, f.articleId());

      DataAccessException error = assertThrows(DataAccessException.class, () -> nested.executeWithoutResult(status ->
          service.update(f.articleId(), new KnowledgeUpdateRequest("Should roll back", "must not persist", null, null, null, List.of(), List.of(-999999999L)), admin(f))));
      assertNotNull(error);

      assertEquals("Atomic edit", jdbc.queryForObject("SELECT title FROM knowledge_articles WHERE id=?", String.class, f.articleId()));
      assertEquals(f.publishedVersionId(), jdbc.queryForObject("SELECT current_version_id FROM knowledge_articles WHERE id=?", Long.class, f.articleId()));
      assertEquals(beforeVersions, jdbc.queryForObject("SELECT count(*) FROM knowledge_versions WHERE article_id=?", Integer.class, f.articleId()));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM knowledge_versions WHERE article_id=? AND content='must not persist'", Integer.class, f.articleId()));
    });
  }

  @Test
  void administratorPendingFilterIncludesPublishedArticleWithDraft() {
    rollbackAfter(() -> {
      Fixture f = fixture("Pending filter");
      Map<String, Object> update = service.update(f.articleId(),
          new KnowledgeUpdateRequest(null, "pending filter draft", null, null, null, null, null), admin(f));
      long draft = number(update.get("newVersionId"));

      Page<KnowledgeArticle> page = service.listPage(admin(f), 1, 100, null, null, null, "IN_REVIEW");
      KnowledgeArticle article = page.items().stream().filter(a -> a.id() == f.articleId()).findFirst().orElseThrow();
      assertEquals(1, article.version());
      assertEquals("PUBLISHED", article.status());
      assertEquals(draft, article.pendingVersionId());

      KnowledgeArticle employeeArticle = service.list(employee(f)).stream().filter(a -> a.id() == f.articleId()).findFirst().orElseThrow();
      assertEquals(f.publishedVersionId(), jdbc.queryForObject("SELECT current_version_id FROM knowledge_articles WHERE id=?", Long.class, f.articleId()));
      assertEquals(1, employeeArticle.version());
      assertNull(employeeArticle.pendingVersionId());
    });
  }

  @Test
  void retractRemovesArticleFromEmployeeVisibility() {
    rollbackAfter(() -> {
      Fixture f = fixture("Retract visibility");
      service.retract(f.articleId(), admin(f));

      assertEquals("ARCHIVED", jdbc.queryForObject("SELECT status FROM knowledge_articles WHERE id=?", String.class, f.articleId()));
      assertTrue(service.list(employee(f)).stream().noneMatch(a -> a.id() == f.articleId()));
      assertThrows(ResponseStatusException.class, () -> service.detail(f.articleId(), employee(f), null));
      assertThrows(ResponseStatusException.class, () -> service.versions(f.articleId(), employee(f)));
      assertThrows(ResponseStatusException.class, () -> service.fileSource(f.articleId(), employee(f), null));
      assertTrue(service.list(admin(f)).stream().anyMatch(a -> a.id() == f.articleId() && "ARCHIVED".equals(a.status())));
    });
  }

  private static Fixture fixture(String label) {
    String suffix = UUID.randomUUID().toString();
    long department = jdbc.queryForObject("INSERT INTO departments(name) VALUES (?) RETURNING id", Long.class,
        "Lifecycle " + label + " " + suffix);
    long admin = insertUser("kb-admin-" + suffix, "KB Admin " + suffix, department);
    long employee = insertUser("kb-employee-" + suffix, "KB Employee " + suffix, department);
    long article = jdbc.queryForObject("INSERT INTO knowledge_articles(title,category,status,visibility,sensitivity) VALUES (?,?,?,?,?) RETURNING id",
        Long.class, label, "LIFECYCLE", "PUBLISHED", "PUBLIC", "INTERNAL");
    long version = jdbc.queryForObject("INSERT INTO knowledge_versions(article_id,version,content,created_by,status) VALUES (?,?,?,?,?) RETURNING id",
        Long.class, article, 1, "published text", admin, "PUBLISHED");
    jdbc.update("UPDATE knowledge_articles SET current_version_id=? WHERE id=?", version, article);
    long item = insertImportItem(article, admin, "published-v1.pdf", "object://published-v1", "PUBLISHED");
    jdbc.update("UPDATE knowledge_versions SET source_import_item_id=? WHERE id=?", item, version);
    service.indexChunks(article, version, "published text");
    return new Fixture(department, admin, employee, article, version, item);
  }

  private static long insertUser(String username, String displayName, long department) {
    return jdbc.queryForObject("INSERT INTO users(username,display_name,department_id,active,account_status) VALUES (?,?,?,true,'ACTIVE') RETURNING id",
        Long.class, username, displayName, department);
  }

  private static long addVersion(Fixture f, int version, String status, String content, String objectKey) {
    long item = objectKey == null ? 0 : insertImportItem(f.articleId(), f.adminId(), "version-" + version + ".pdf", objectKey, "REVIEW");
    long id = jdbc.queryForObject("INSERT INTO knowledge_versions(article_id,version,content,created_by,status) VALUES (?,?,?,?,?) RETURNING id",
        Long.class, f.articleId(), version, content, f.adminId(), status);
    if (item != 0) jdbc.update("UPDATE knowledge_versions SET source_import_item_id=? WHERE id=?", item, id);
    service.indexChunks(f.articleId(), id, content);
    return id;
  }

  private static long addImportItem(Fixture f, String fileName, String objectKey) {
    return insertImportItem(f.articleId(), f.adminId(), fileName, objectKey, "REVIEW");
  }

  private static long insertImportItem(long article, long createdBy, String fileName, String objectKey, String status) {
    long batch = jdbc.queryForObject("INSERT INTO import_batches(name,created_by,total_items,status) VALUES (?,?,1,'COMPLETED') RETURNING id",
        Long.class, "Lifecycle batch " + UUID.randomUUID(), createdBy);
    return jdbc.queryForObject("INSERT INTO import_items(batch_id,file_name,content_type,size_bytes,object_key,status,article_id,created_by) VALUES (?,?,?,?,?,?,?,?) RETURNING id",
        Long.class, batch, fileName, "application/pdf", 12L, objectKey, status, article, createdBy);
  }

  private static UserContext admin(Fixture f) {
    return new UserContext(f.adminId(), "kb-admin", "KB Admin", "ADMIN", f.departmentId(), "Lifecycle", List.of());
  }

  private static UserContext employee(Fixture f) {
    return new UserContext(f.employeeId(), "kb-employee", "KB Employee", "EMPLOYEE", f.departmentId(), "Lifecycle", List.of());
  }

  private static void assertArticleState(long article, String status, long currentVersion) {
    Map<String, Object> row = jdbc.queryForMap("SELECT status,current_version_id FROM knowledge_articles WHERE id=?", article);
    assertEquals(status, row.get("status"));
    assertEquals(currentVersion, number(row.get("current_version_id")));
  }

  private static long number(Object value) {
    assertNotNull(value);
    return ((Number) value).longValue();
  }

  @SuppressWarnings("unchecked")
  private static Object mapValue(Map<String, Object> root, String mapKey, String valueKey) {
    return ((Map<String, Object>) root.get(mapKey)).get(valueKey);
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for the integration test");
    return value;
  }

  private static void rollbackAfter(ThrowingRunnable test) {
    tx.executeWithoutResult(status -> {
      try {
        test.run();
      } finally {
        status.setRollbackOnly();
      }
    });
  }

  @FunctionalInterface
  private interface ThrowingRunnable { void run(); }
}
