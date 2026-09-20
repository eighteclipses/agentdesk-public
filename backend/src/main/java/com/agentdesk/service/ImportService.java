package com.agentdesk.service;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 异步导入流水线。
 *
 * <p>事实源是数据库：{@code import_batches / import_items / processing_events}。4 个 worker
 * 线程通过 {@code FOR UPDATE SKIP LOCKED} 认领 QUEUED 项，逐项走
 * 解析(agent /v1/parse-document) → 归一化 → 分块 → 索引，最终进入 REVIEW 等待管理员发布。
 * 每阶段写 processing_events 并经 SSE 广播；断线后前端可重放（按批次订阅时回放历史事件）。
 *
 * <p>同一 sha256 的内容：默认策略 skip（标记 DUPLICATE 跳过）；策略 version 时作为文章的
 * 新版本导入（版本策略下新版本暂不生效，审核发布后才切换 current_version_id）。
 */
@Service
public class ImportService {
  private static final Logger log = LoggerFactory.getLogger(ImportService.class);
  private static final Set<String> ALLOWED = Set.of(".pdf", ".docx", ".txt", ".md", ".markdown");
  private static final Map<String, String> PARSER_BY_EXT = Map.of(".pdf", "pypdf", ".docx", "docx", ".txt", "text", ".md", "markdown", ".markdown", "markdown");
  private static final Set<String> ACTIVE = Set.of("QUEUED", "PARSING", "NORMALIZING", "CHUNKING", "INDEXING");

  private final JdbcTemplate jdbc; private final StorageService storage; private final Chunker chunker;
  private final AuthContext auth; private final AuditService audit; private final ObjectMapper mapper;
  private final KnowledgeService knowledge; private final TransactionTemplate tx;
  private final HttpClient http; private final String agentBaseUrl; private final String agentToken;
  private final ThreadPoolExecutor workers;
  private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

  public ImportService(JdbcTemplate jdbc, StorageService storage, Chunker chunker, AuthContext auth,
                       AuditService audit, ObjectMapper mapper, KnowledgeService knowledge,
                       PlatformTransactionManager transactionManager,
                       @org.springframework.beans.factory.annotation.Value("${agent.base-url:http://localhost:8000}") String agentBaseUrl,
                       @org.springframework.beans.factory.annotation.Value("${agent.service-token:}") String agentToken) {
    this.jdbc=jdbc; this.storage=storage; this.chunker=chunker; this.auth=auth; this.audit=audit;
    this.mapper=mapper; this.knowledge=knowledge; this.tx=new TransactionTemplate(transactionManager);
    this.agentBaseUrl=agentBaseUrl; this.agentToken=agentToken;
    this.http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    this.workers=new ThreadPoolExecutor(4,4,60,TimeUnit.SECONDS,new SynchronousQueue<>(), r->{
      Thread t=new Thread(r,"import-worker-"+i.incrementAndGet()); t.setDaemon(true); return t; });
  }
  private static final java.util.concurrent.atomic.AtomicInteger i=new java.util.concurrent.atomic.AtomicInteger();

  @EventListener(ApplicationReadyEvent.class)
  public void startWorkers() {
    // 崩溃恢复：把上次进程残留的“处理中”项重新排队
    jdbc.update("UPDATE import_items SET status='QUEUED',progress=0,error=null,updated_at=now() WHERE status IN ('PARSING','NORMALIZING','CHUNKING','INDEXING')");
    for (int w=0;w<4;w++) workers.execute(this::workerLoop);
  }

  private void workerLoop() {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        Long itemId = claimNext();
        if (itemId == null) { Thread.sleep(800); continue; }
        process(itemId);
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      catch (Exception e) { log.warn("worker 异常", e); try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; } }
    }
  }

  /** 事务内认领一个 QUEUED 项；SKIP LOCKED 保证多 worker 互不冲突。 */
  private Long claimNext() {
    return tx.execute(s -> {
      List<Long> ids = jdbc.query("SELECT id FROM import_items WHERE status='QUEUED' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", (rs,n)->rs.getLong(1));
      if (ids.isEmpty()) return null;
      jdbc.update("UPDATE import_items SET status='PARSING',progress=5,updated_at=now() WHERE id=?", ids.get(0));
      return ids.get(0);
    });
  }

  // ---------- 批次创建 ----------

  /**
   * 批次创建保持原子性：先整体校验扩展名，再逐个上传 MinIO，最后在单个事务内写入批次与条目；
   * 任一步失败都会清理已上传对象并回滚数据库行，不产生半成品批次。
   *
   * @param sourcePaths 与 files 一一对应的文件夹相对路径（前端 webkitRelativePath），可为 null
   */
  public BatchCreateResult createBatch(String name, MultipartFile[] files, String[] sourcePaths, Long departmentId, String provider, String duplicateStrategy, UserContext user) {
    auth.requireAdmin(user);
    if (files==null||files.length==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请至少选择一个文件");
    if (files.length>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"单批次最多 100 个文件");
    String strategy="version".equalsIgnoreCase(duplicateStrategy)?"version":"skip";
    // 先整体校验，避免传到一半才失败留下孤儿批次
    for (MultipartFile file : files) {
      if (file==null||file.isEmpty()) continue;
      String ext=extension(Objects.toString(file.getOriginalFilename(),"file"));
      if (!ALLOWED.contains(ext)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, Objects.toString(file.getOriginalFilename(),"file")+"：仅支持 PDF、DOCX、TXT、Markdown");
    }
    List<String[]> staged=new ArrayList<>(); // [fileName, objectKey]
    try {
      for (MultipartFile file : files) {
        if (file==null||file.isEmpty()) continue;
        staged.add(new String[]{Objects.toString(file.getOriginalFilename(),"file"), storage.uploadDocument(file)});
      }
      if (staged.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"没有可处理的文件");
      return tx.execute(s -> {
        Long batchId=jdbc.queryForObject("INSERT INTO import_batches(name,created_by,department_id,provider,duplicate_strategy,total_items) VALUES (?,?,?,?,?,?) RETURNING id",
            Long.class, Objects.toString(name,"导入批次 "+LocalDate.now()),user.id(),departmentId,provider,strategy,staged.size());
        List<Long> itemIds=new ArrayList<>();
        int i=0;
        for (MultipartFile file : files) {
          if (file==null||file.isEmpty()) continue;
          String fname=staged.get(i)[0]; String key=staged.get(i)[1]; i++;
          try {
            String sha=sha256(file.getBytes());
            String path=sourcePaths!=null&&i-1<sourcePaths.length?sourcePaths[i-1]:null;
            Long itemId=jdbc.queryForObject("INSERT INTO import_items(batch_id,file_name,content_type,size_bytes,sha256,parser,object_key,source_path,status,created_by) VALUES (?,?,?,?,?,?,?,?,'QUEUED',?) RETURNING id",
                Long.class,batchId,fname,file.getContentType(),file.getSize(),sha,PARSER_BY_EXT.getOrDefault(extension(fname),"text"),key,path,user.id());
            itemIds.add(itemId);
          } catch (java.io.IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"读取文件失败："+fname); }
        }
        audit.log(user.id(),"IMPORT_BATCH_CREATED","IMPORT_BATCH",batchId,Map.of("items",itemIds.size(),"strategy",strategy));
        return new BatchCreateResult(batchId,itemIds.size(),itemIds);
      });
    } catch (RuntimeException e) {
      // 事务回滚或上传/校验失败：清理已上传的对象，避免孤儿数据
      for (String[] pair : staged) storage.deleteQuietly(pair[1]);
      throw e;
    }
  }

  // ---------- 单项处理 ----------

  private void process(long itemId) {
    Map<String,Object> item;
    try { item=jdbc.queryForList("SELECT i.*,b.department_id AS batch_department,b.provider AS batch_provider,b.duplicate_strategy AS batch_strategy FROM import_items i JOIN import_batches b ON b.id=i.batch_id WHERE i.id=?",itemId).get(0); }
    catch (IndexOutOfBoundsException e) { return; }
    if (!"PARSING".equals(item.get("status"))) return; // 认领后被取消
    long batchId=((Number)item.get("batch_id")).longValue();
    String fileName=Objects.toString(item.get("file_name"),"");
    try {
      event(itemId,batchId,"PARSING","从对象存储取回原件，交给解析服务",5,fileName);
      byte[] bytes=storage.downloadBytes((String)item.get("object_key"));
      Map<String,Object> parsed=callAgentParse(fileName,(String)item.get("content_type"),bytes);
      String markdown=Objects.toString(parsed.get("markdown"),"").strip();
      String parser=Objects.toString(parsed.get("parser"),"");
      if (markdown.isBlank()) throw new IllegalStateException("解析结果为空，文件可能是纯图片或已损坏");
      event(itemId,batchId,"NORMALIZING","解析完成（"+parser+"，"+markdown.length()+" 字符），正在提取标题与摘要",40,fileName);
      String title=extractTitle(markdown,fileName);
      event(itemId,batchId,"CHUNKING","按标题层级分块",60,fileName);
      List<Chunker.Chunk> chunks=chunker.chunk(markdown);
      if (chunks.isEmpty()) chunks=List.of(new Chunker.Chunk(0,"",markdown,null));
      event(itemId,batchId,"INDEXING","写入 "+chunks.size()+" 个知识分块并建立全文索引",80,fileName);

      final String fTitle=title; final String fParser=parser.isBlank()?Objects.toString(item.get("parser"),"text"):parser;
      final String sha=(String)item.get("sha256");
      final Number dept=(Number)item.get("batch_department");
      final Long createdBy=((Number)item.get("created_by")).longValue();
      final List<Chunker.Chunk> chunkList=chunks;
      Map<String,Object> outcome=tx.execute(s -> {
        Map<String,Object> dup=findDuplicate(sha,itemId);
        String strategy=Objects.toString(item.get("batch_strategy"),"skip");
        if (dup!=null && !"version".equals(strategy)) {
          jdbc.update("UPDATE import_items SET status='DUPLICATE',duplicate_of=?,updated_at=now() WHERE id=?",dup.get("id"),itemId);
          return Map.of("result","DUPLICATE","id",dup.get("id"),"articleId",dup.get("articleId"));
        }
        Long articleId;
        boolean reuse=dup!=null;
        if (reuse) {
          articleId=((Number)dup.get("articleId")).longValue();
          // 版本策略沿用本批次声明的可见范围，而不是继承旧文章的设置（否则新批次收紧部门时会漏授权）
          String visibility=dept!=null?"DEPARTMENT":"PUBLIC";
          jdbc.update("UPDATE knowledge_articles SET visibility=?,updated_at=now() WHERE id=?",visibility,articleId);
          jdbc.update("DELETE FROM knowledge_department_access WHERE article_id=?",articleId);
          if (dept!=null) jdbc.update("INSERT INTO knowledge_department_access(article_id,department_id) VALUES (?,?) ON CONFLICT DO NOTHING",articleId,dept.longValue());
        }
        else {
          String visibility=dept!=null?"DEPARTMENT":"PUBLIC";
          articleId=jdbc.queryForObject("INSERT INTO knowledge_articles(title,category,status,visibility) VALUES (?,?, 'IN_REVIEW',?) RETURNING id",Long.class,fTitle,"DOCUMENT",visibility);
          if (dept!=null) jdbc.update("INSERT INTO knowledge_department_access(article_id,department_id) VALUES (?,?) ON CONFLICT DO NOTHING",articleId,dept.longValue());
        }
        Long versionId=jdbc.queryForObject("INSERT INTO knowledge_versions(article_id,version,content,created_by,status) VALUES (?,(SELECT coalesce(max(version),0)+1 FROM knowledge_versions WHERE article_id=?),?,?,'DRAFT') RETURNING id",
            Long.class,articleId,articleId,markdown,createdBy);
        if (!reuse) jdbc.update("UPDATE knowledge_articles SET current_version_id=?,updated_at=now() WHERE id=?",versionId,articleId);
        int index=0;
        for (Chunker.Chunk c : chunkList) {
          jdbc.update("INSERT INTO knowledge_chunks(article_id,version_id,chunk_index,heading_path,content,page_no,char_count) VALUES (?,?,?,?,?,?,?)",
              articleId,versionId,index++,c.headingPath(),c.content(),c.page(),c.content().length());
        }
        jdbc.update("UPDATE knowledge_versions SET chunk_ready=true WHERE id=?",versionId);
        jdbc.update("UPDATE import_items SET status='REVIEW',progress=100,article_id=?,summary=?,parser=?,updated_at=now() WHERE id=?",articleId,summarize(markdown),fParser,itemId);
        return Map.of("result","REVIEW","articleId",articleId,"versionId",versionId);
      });
      String result=Objects.toString(outcome.get("result"),"");
      if ("DUPLICATE".equals(result)) {
        event(itemId,batchId,"DUPLICATE","与已导入文件内容相同（sha256 一致 #"+outcome.get("id")+"），已跳过；如需更新请改用“作为新版本导入”",100,fileName);
        audit.log(createdBy,"IMPORT_ITEM_DUPLICATED","IMPORT_ITEM",itemId,Map.of("duplicateOf",outcome.get("id")));
      } else {
        event(itemId,batchId,"REVIEW","已生成文章草稿并完成分块索引，等待审核发布",100,fileName);
        audit.log(createdBy,"IMPORT_ITEM_INDEXED","KNOWLEDGE",((Number)outcome.get("articleId")).longValue(),Map.of("itemId",itemId));
        // 向量化放事务外异步执行：失败只影响向量召回，不阻断导入管线（检索自动降级纯 FTS）
        long versionId=((Number)outcome.get("versionId")).longValue();
        java.util.concurrent.CompletableFuture.runAsync(() -> knowledge.embedChunksIfConfigured(versionId));
      }
    } catch (Exception e) {
      String message=rootMessage(e);
      jdbc.update("UPDATE import_items SET status='FAILED',error=?,updated_at=now() WHERE id=?",message,itemId);
      event(itemId,batchId,"FAILED",message,100,fileName);
      audit.log(((Number)item.get("created_by")).longValue(),"IMPORT_ITEM_FAILED","IMPORT_ITEM",itemId,Map.of("error",message));
    }
    refreshBatchStatus(batchId);
  }

  /** 同一 sha256 的历史项：取最近一个已生成文章且处于 REVIEW/PUBLISHED 状态的导入项。 */
  private Map<String,Object> findDuplicate(String sha, long selfId) {
    if (sha==null||sha.isBlank()) return null;
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,article_id FROM import_items WHERE sha256=? AND article_id IS NOT NULL AND status IN ('REVIEW','PUBLISHED') AND id<>? ORDER BY id DESC LIMIT 1",sha,selfId);
    return rows.isEmpty()?null:rows.get(0);
  }

  // ---------- 对外操作 ----------

  public Map<String,Object> batch(long id, UserContext user) {
    auth.requireAdmin(user);
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,name,status,duplicate_strategy,total_items,created_at,created_by FROM import_batches WHERE id=?",id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"导入批次不存在");
    Map<String,Object> result=new LinkedHashMap<>(rows.get(0));
    result.put("items", items(id));
    return result;
  }

  public List<ImportBatchSummary> batches(UserContext user) {
    auth.requireAdmin(user);
    return jdbc.query("""
        SELECT b.id,b.name,b.status,b.total_items,b.created_at,
          count(*) FILTER (WHERE i.status='QUEUED'),
          count(*) FILTER (WHERE i.status IN ('PARSING','NORMALIZING','CHUNKING','INDEXING')),
          count(*) FILTER (WHERE i.status='REVIEW'),
          count(*) FILTER (WHERE i.status='PUBLISHED'),
          count(*) FILTER (WHERE i.status='FAILED'),
          count(*) FILTER (WHERE i.status='DUPLICATE')
        FROM import_batches b LEFT JOIN import_items i ON i.batch_id=b.id
        GROUP BY b.id ORDER BY b.created_at DESC LIMIT 50""",
        (rs,n)->new ImportBatchSummary(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getTimestamp(5).toInstant(),
            rs.getLong(6),rs.getLong(7),rs.getLong(8),rs.getLong(9),rs.getLong(10),rs.getLong(11)));
  }

  public List<ImportItem> items(long batchId) {
    return jdbc.query("SELECT id,batch_id,file_name,content_type,size_bytes,parser,status,progress,error,summary,article_id,duplicate_of,source_path,created_at,updated_at FROM import_items WHERE batch_id=? ORDER BY id",
        (rs,n)->new ImportItem(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getString(7),rs.getInt(8),rs.getString(9),rs.getString(10),(Long)rs.getObject(11),(Long)rs.getObject(12),rs.getString(13),rs.getTimestamp(14).toInstant(),rs.getTimestamp(15).toInstant()),batchId);
  }

  /** 导入项原件下载（任意状态，含失败/取消/重复），供排障取回原文件。 */
  public Map<String,Object> itemFile(long itemId, UserContext user) {
    auth.requireAdmin(user);
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT file_name AS \"fileName\",content_type AS \"contentType\",object_key AS \"objectKey\" FROM import_items WHERE id=?",itemId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"导入项不存在");
    Map<String,Object> row=rows.get(0);
    if (row.get("objectKey")==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"原件不存在");
    return row;
  }

  public ImportItem retry(long itemId, UserContext user) {
    auth.requireAdmin(user);
    String status=jdbc.queryForObject("SELECT status FROM import_items WHERE id=?",String.class,itemId);
    if (!"FAILED".equals(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"只有失败项可以重试");
    jdbc.update("UPDATE import_items SET status='QUEUED',error=null,progress=0,updated_at=now() WHERE id=?",itemId);
    long batchId=jdbc.queryForObject("SELECT batch_id FROM import_items WHERE id=?",Long.class,itemId);
    event(itemId,batchId,"QUEUED","人工重试，已重新排队",0,null);
    audit.log(user.id(),"IMPORT_ITEM_RETRIED","IMPORT_ITEM",itemId,Map.of());
    return items(batchId).stream().filter(x->x.id()==itemId).findFirst().orElseThrow();
  }

  public ImportItem cancel(long itemId, UserContext user) {
    auth.requireAdmin(user);
    String status=jdbc.queryForObject("SELECT status FROM import_items WHERE id=?",String.class,itemId);
    if (!Set.of("QUEUED","FAILED").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"处理中的项无法取消");
    jdbc.update("UPDATE import_items SET status='CANCELED',updated_at=now() WHERE id=?",itemId);
    long batchId=jdbc.queryForObject("SELECT batch_id FROM import_items WHERE id=?",Long.class,itemId);
    event(itemId,batchId,"CANCELED","已取消",100,null);
    refreshBatchStatus(batchId);
    return items(batchId).stream().filter(x->x.id()==itemId).findFirst().orElseThrow();
  }

  /** 批量发布：批次内所有 REVIEW 项的文章走审核通过，发布当前（最新）版本。 */
  public int publishBatch(long batchId, UserContext user) {
    auth.requireAdmin(user);
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,article_id,file_name FROM import_items WHERE batch_id=? AND status='REVIEW' AND article_id IS NOT NULL",batchId);
    for (Map<String,Object> row : rows) {
      knowledge.review(((Number)row.get("article_id")).longValue(),true,"导入批次批量发布",user);
      event(((Number)row.get("id")).longValue(),batchId,"PUBLISHED","已发布，员工知识问答即刻可检索",100,Objects.toString(row.get("file_name"),""));
    }
    refreshBatchStatus(batchId);
    audit.log(user.id(),"IMPORT_BATCH_PUBLISHED","IMPORT_BATCH",batchId,Map.of("published",rows.size()));
    return rows.size();
  }

  private void refreshBatchStatus(long batchId) {
    jdbc.update("""
        UPDATE import_batches b SET status=CASE
          WHEN EXISTS (SELECT 1 FROM import_items i WHERE i.batch_id=b.id AND i.status IN ('QUEUED','PARSING','NORMALIZING','CHUNKING','INDEXING')) THEN 'PROCESSING'
          WHEN EXISTS (SELECT 1 FROM import_items i WHERE i.batch_id=b.id AND i.status IN ('FAILED','REJECTED')) THEN 'PARTIAL_FAILED'
          ELSE 'COMPLETED' END
        WHERE b.id=?""",batchId);
  }

  // ---------- SSE ----------

  public SseEmitter subscribe(long batchId, UserContext user) {
    auth.requireAdmin(user);
    SseEmitter emitter=new SseEmitter(0L);
    List<SseEmitter> list=emitters.computeIfAbsent(batchId,k->Collections.synchronizedList(new ArrayList<>()));
    try {
      Map<String,Object> init=new LinkedHashMap<>();
      init.put("items", items(batchId));
      init.put("events", jdbc.queryForList("SELECT e.item_id AS \"itemId\",e.stage,e.message,e.progress,e.created_at AS \"at\" FROM processing_events e JOIN import_items i ON i.id=e.item_id WHERE i.batch_id=? ORDER BY e.id",batchId));
      emitter.send(SseEmitter.event().name("init").data(init));
    } catch (Exception ignored) { /* 连接已断开 */ }
    list.add(emitter);
    emitter.onCompletion(() -> list.remove(emitter));
    emitter.onTimeout(() -> list.remove(emitter));
    emitter.onError(t -> list.remove(emitter));
    return emitter;
  }

  private void event(long itemId, long batchId, String stage, String message, int progress, String fileName) {
    try { jdbc.update("INSERT INTO processing_events(item_id,stage,message,progress) VALUES (?,?,?,?)",itemId,stage,message,progress); } catch (Exception e) { log.warn("事件写入失败",e); }
    List<SseEmitter> list=emitters.get(batchId);
    if (list==null||list.isEmpty()) return;
    Map<String,Object> payload=new LinkedHashMap<>();
    payload.put("itemId",itemId); payload.put("stage",stage); payload.put("message",message);
    payload.put("progress",progress); payload.put("at",Instant.now().toString());
    if (fileName!=null) payload.put("fileName",fileName);
    for (SseEmitter emitter : List.copyOf(list)) {
      try { emitter.send(SseEmitter.event().name("progress").data(payload)); }
      catch (Exception e) { list.remove(emitter); try { emitter.complete(); } catch (Exception ignored) {} }
    }
  }

  @Scheduled(fixedDelay = 15000)
  public void heartbeat() {
    for (Map.Entry<Long,List<SseEmitter>> entry : emitters.entrySet()) {
      for (SseEmitter emitter : List.copyOf(entry.getValue())) {
        try { emitter.send(SseEmitter.event().comment("ping")); }
        catch (Exception e) { entry.getValue().remove(emitter); try { emitter.complete(); } catch (Exception ignored) {} }
      }
    }
  }

  // ---------- 辅助 ----------

  private String extension(String name) {
    int dot=name.lastIndexOf('.');
    return dot>=0?name.substring(dot).toLowerCase(Locale.ROOT):"";
  }

  private String sha256(byte[] bytes) {
    try {
      MessageDigest md=MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 不可用", e); }
  }

  private static final Pattern H1=Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*\\s*$");

  private String extractTitle(String markdown, String fileName) {
    for (String line : markdown.split("\r?\n")) {
      if (line.isBlank()) continue;
      Matcher m=H1.matcher(line.strip());
      if (m.matches()) return m.group(1).strip();
      break;
    }
    int dot=fileName.lastIndexOf('.');
    return dot>0?fileName.substring(0,dot):fileName;
  }

  private String summarize(String markdown) {
    String text=markdown.replaceAll("<!--\\s*page:\\d+\\s*-->","").replaceAll("[#>*`\\-]"," ").replaceAll("\\s+"," ").strip();
    return text.length()<=200?text:text.substring(0,200)+"…";
  }

  /** 调 agent /v1/parse-document（纯解析，无 LLM 依赖），失败时提取 agent 返回的可读原因。 */
  private Map<String,Object> callAgentParse(String fileName, String contentType, byte[] bytes) throws Exception {
    String boundary="----AgentDeskImport"+UUID.randomUUID().toString().replace("-","");
    ByteArrayOutputStream buffer=new ByteArrayOutputStream();
    buffer.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+fileName.replace("\"","_")+"\"\r\nContent-Type: "+Objects.toString(contentType,"application/octet-stream")+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    buffer.writeBytes(bytes);
    buffer.writeBytes(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
    HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(agentBaseUrl+"/v1/parse-document"))
        .timeout(Duration.ofSeconds(120))
        .header("Content-Type","multipart/form-data; boundary="+boundary)
        .header("Accept","application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(buffer.toByteArray()));
    if (!agentToken.isBlank()) request.header("X-Agent-Token",agentToken);
    HttpResponse<String> response=http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode()>=300) throw new IllegalStateException(readableAgentError(response.statusCode(),response.body()));
    return mapper.readValue(response.body(),new TypeReference<Map<String,Object>>(){});
  }

  private String readableAgentError(int status, String body) {
    try { String detail=mapper.readTree(body).path("detail").asText(""); if (!detail.isBlank()) return detail; } catch (Exception ignored) {}
    return "解析服务返回 "+status;
  }

  private String rootMessage(Throwable e) {
    Throwable t=e;
    while (t.getCause()!=null && t.getCause()!=t) t=t.getCause();
    String msg=t.getMessage();
    return msg==null||msg.isBlank()?t.getClass().getSimpleName():msg;
  }
}