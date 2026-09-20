package com.agentdesk.service;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/**
 * 企业知识：列表 / 分块检索 / 详情与版本 / 审核发布 / 撤回。
 * 检索单位是 knowledge_chunks，引用形如 article:{articleId}/version:{versionId}#c{chunkIndex}，
 * 前端可据此定位到文件+版本+小节（PDF 命中还带页码）。
 *
 * <p>可见性规则（非管理员）：仅 PUBLISHED 且（visibility=PUBLIC 或部门授权）；
 * sensitivity=CONFIDENTIAL 的文章无论 visibility 如何都必须有部门授权。
 */
@Service
public class KnowledgeService {
  private final JdbcTemplate jdbc; private final AuthContext auth; private final AuditService audit; private final Chunker chunker; private final NotificationService notifications; private final EmbeddingService embeddings;
  private final org.springframework.transaction.support.TransactionTemplate tx;
  public KnowledgeService(JdbcTemplate jdbc, AuthContext auth, AuditService audit, Chunker chunker, NotificationService notifications, EmbeddingService embeddings, org.springframework.transaction.PlatformTransactionManager transactionManager){
    this.jdbc=jdbc;this.auth=auth;this.audit=audit;this.chunker=chunker;this.notifications=notifications;this.embeddings=embeddings;this.tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);}

  /** 启动回填：V3 种子数据、旧导入路径、手动创建的文章没有分块，补齐后参与分块检索。 */
  @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void backfillChunks() {
    java.util.List<long[]> rows=jdbc.query("SELECT v.id,v.article_id FROM knowledge_versions v WHERE v.chunk_ready=false AND v.content IS NOT NULL AND length(v.content)>0",(rs,n)->new long[]{rs.getLong(1),rs.getLong(2)});
    int done=0;
    for (long[] row : rows) { try { indexChunks(row[1],row[0],jdbc.queryForObject("SELECT content FROM knowledge_versions WHERE id=?",String.class,row[0])); done++; } catch (Exception e) { /* 单版本失败不影响启动 */ } }
    if (done>0) org.slf4j.LoggerFactory.getLogger(KnowledgeService.class).info("分块回填完成：{} 个版本",done);
  }

  /** 为指定版本生成分块并置 chunk_ready；幂等（先清后写）。 */
  public void indexChunks(long articleId, long versionId, String content) {
    if (content==null||content.isBlank()) return;
    java.util.List<Chunker.Chunk> chunks=chunker.chunk(content);
    if (chunks.isEmpty()) return;
    jdbc.update("DELETE FROM knowledge_chunks WHERE version_id=?",versionId);
    int index=0;
    for (Chunker.Chunk c : chunks) {
      jdbc.update("INSERT INTO knowledge_chunks(article_id,version_id,chunk_index,heading_path,content,page_no,char_count) VALUES (?,?,?,?,?,?,?)",
          articleId,versionId,index++,c.headingPath(),c.content(),c.page(),c.content().length());
    }
    jdbc.update("UPDATE knowledge_versions SET chunk_ready=true WHERE id=?",versionId);
  }

  /** 为指定版本未向量化的分块计算 embedding（配置 agent.embed-model 时生效）；失败静默，检索保持纯 FTS。 */
  public void embedChunksIfConfigured(long versionId) {
    if (!embeddings.enabled()) return;
    try {
      List<Map<String,Object>> rows=jdbc.queryForList("SELECT id, content FROM knowledge_chunks WHERE version_id=? AND embedding IS NULL ORDER BY chunk_index",versionId);
      for (int start=0; start<rows.size(); start+=32) {
        List<Map<String,Object>> batch=rows.subList(start,Math.min(start+32,rows.size()));
        List<String> texts=batch.stream().map(r->Objects.toString(r.get("content"),"")).toList();
        List<float[]> vectors=embeddings.embedBatch(texts);
        if (vectors.size()!=texts.size()) return; // 服务不可用：剩余批次一并跳过
        for (int i=0;i<batch.size();i++)
          jdbc.update("UPDATE knowledge_chunks SET embedding=?::vector, embedding_model=? WHERE id=?",
              vectorLiteral(vectors.get(i)), embeddings.model(), ((Number)batch.get(i).get("id")).longValue());
      }
    } catch (Exception e) { /* 单版本失败不影响主流程 */ }
  }

  /** 启动回填向量：为存量分块补算 embedding（版本数上限 200，异常静默），使混合检索对旧数据生效。 */
  @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void backfillEmbeddings() {
    if (!embeddings.enabled()) return;
    try {
      List<Long> versions=jdbc.query("SELECT DISTINCT version_id FROM knowledge_chunks WHERE embedding IS NULL ORDER BY version_id LIMIT 200",(rs,n)->rs.getLong(1));
      versions.forEach(this::embedChunksIfConfigured);
      if (!versions.isEmpty()) org.slf4j.LoggerFactory.getLogger(KnowledgeService.class).info("向量回填完成：{} 个版本",versions.size());
    } catch (Exception e) { /* 回填失败不影响启动 */ }
  }

  /** 非管理员可见性片段：PUBLISHED 且（PUBLIC 或部门授权）；CONFIDENTIAL 必须部门授权。 */
  private static final String VISIBILITY_FILTER =
      " AND (a.visibility='PUBLIC' OR EXISTS (SELECT 1 FROM knowledge_department_access da WHERE da.article_id=a.id AND da.department_id=?))" +
      " AND (a.sensitivity<>'CONFIDENTIAL' OR EXISTS (SELECT 1 FROM knowledge_department_access da WHERE da.article_id=a.id AND da.department_id=?))";

  public List<KnowledgeArticle> list(UserContext user){
    String base="SELECT a.id,a.title,a.category,a.status,v.version,a.visibility,a.updated_at FROM knowledge_articles a JOIN knowledge_versions v ON v.id=a.current_version_id";
    if(!user.admin()) base+=" WHERE a.status='PUBLISHED'"+VISIBILITY_FILTER;
    base+=" ORDER BY a.updated_at DESC";
    return user.admin()?jdbc.query(base,(rs,n)->mapArticle(rs,n)):jdbc.query(base,(rs,n)->mapArticle(rs,n),user.departmentId(),user.departmentId());
  }

  /** 分页列表：非管理员恒为 PUBLISHED 且有权限的文章；管理员可再按 status 过滤（审核台用）。 */
  public Page<KnowledgeArticle> listPage(UserContext user, int page, int size, String q, String category, String tag, String status){
    int p=Math.max(1,page); int s=Math.min(Math.max(1,size),100);
    StringBuilder sql=new StringBuilder("SELECT a.id,a.title,a.category,a.status,v.version,a.visibility,a.updated_at FROM knowledge_articles a JOIN knowledge_versions v ON v.id=a.current_version_id");
    List<Object> args=new ArrayList<>();
    if(!user.admin()){ sql.append(" WHERE a.status='PUBLISHED'").append(VISIBILITY_FILTER); args.add(user.departmentId()); args.add(user.departmentId()); }
    else sql.append(" WHERE TRUE");
    if(user.admin()&&status!=null&&!status.isBlank()){ sql.append(" AND a.status=?"); args.add(status.toUpperCase(java.util.Locale.ROOT)); }
    if(q!=null&&!q.isBlank()){ sql.append(" AND lower(a.title) LIKE lower(?)"); args.add("%"+q.strip()+"%"); }
    if(category!=null&&!category.isBlank()){ sql.append(" AND a.category=?"); args.add(category); }
    if(tag!=null&&!tag.isBlank()){ sql.append(" AND EXISTS (SELECT 1 FROM article_tags at2 JOIN knowledge_tags t ON t.id=at2.tag_id WHERE at2.article_id=a.id AND lower(t.name)=lower(?))"); args.add(tag); }
    Long total=jdbc.queryForObject("SELECT count(*) FROM ("+sql+") t",Long.class,args.toArray());
    List<Object> pageArgs=new ArrayList<>(args); pageArgs.add(s); pageArgs.add((p-1)*s);
    List<KnowledgeArticle> items=jdbc.query(sql.append(" ORDER BY a.updated_at DESC LIMIT ? OFFSET ?").toString(),(rs,n)->mapArticle(rs,n),pageArgs.toArray());
    return new Page<>(items,total==null?0:total);
  }
  private KnowledgeArticle mapArticle(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
    return new KnowledgeArticle(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getString(6),rs.getTimestamp(7).toInstant());
  }

  /** 每文章在检索结果中的最大命中数：避免单篇长文霸榜，让引用更均衡。 */
  private static final int MAX_HITS_PER_ARTICLE = 2;

  private static String ftsFn(boolean orMode) { return orMode ? "agentdesk_fts_query_tokens_or" : "agentdesk_fts_query_tokens"; }

  /** 旧签名兼容（AgentService 的分类/草稿/推荐上下文仍用列表返回）：取第一页 20 条。 */
  public List<KnowledgeHit> search(String term, UserContext user){
    if(term==null||term.isBlank()) return List.of();
    return searchPage(term, user, null, null, null, null).items();
  }

  /**
   * 分块检索（分页 + 分类/标签过滤）：
   * AND 精确优先（CJK 单字 AND），候选不足一页时 OR 放宽——修复长查询"要求每个字都出现"的召回断崖；
   * 配置了 agent.embed-model 且向量可用时，与向量召回做 RRF 融合（同可见性过滤）。
   */
  public Page<KnowledgeHit> searchPage(String term, UserContext user, String category, String tag, Integer page, Integer size){
    if(term==null||term.isBlank()) return new Page<>(List.of(),0);
    int p=Math.max(1,page==null?1:page); int s=Math.min(Math.max(1,size==null?20:size),50);
    if(embeddings.enabled()){
      java.util.Optional<float[]> qv=embeddings.embedOne(term);
      if(qv.isPresent()){
        int candidates=s*2;
        List<KnowledgeHit> fts=ftsHits(term,user,category,tag,ftsOrFallback(term,user,category,tag,s),1,candidates).items();
        List<KnowledgeHit> vec=vectorHits(qv.get(),user,category,tag,candidates);
        List<KnowledgeHit> merged=RrfMerger.merge(fts,vec,60,MAX_HITS_PER_ARTICLE);
        int from=Math.min((p-1)*s,merged.size()); int to=Math.min(p*s,merged.size());
        return new Page<>(merged.subList(from,to),merged.size());
      }
    }
    return ftsHits(term,user,category,tag,ftsOrFallback(term,user,category,tag,s),p,s);
  }

  /** AND 精确召回不足一页时放宽为 OR（查询级决定，保证分页内部一致）。 */
  private boolean ftsOrFallback(String term, UserContext user, String category, String tag, int size){
    return ftsCount(term,user,category,tag,false)<size;
  }

  /** 分块检索的公共段：rank 计算 + 可见性/分类/标签过滤 + 命中条件；按 SQL 中出现顺序填充参数。 */
  private String chunkFilterSql(String term, UserContext user, String category, String tag, String fn, List<Object> args){
    StringBuilder sql=new StringBuilder(
        "SELECT c.id AS chunk_id, c.article_id, v.id AS version_id, c.chunk_index, a.title, c.heading_path, c.page_no, c.content, "+
        "ts_rank(c.search_vector, to_tsquery('simple',"+fn+"(?))) AS rank "+
        "FROM knowledge_chunks c JOIN knowledge_articles a ON a.id=c.article_id JOIN knowledge_versions v ON v.id=c.version_id "+
        "WHERE a.status='PUBLISHED' AND v.id=a.current_version_id");
    args.add(term);
    if(!user.admin()){ sql.append(VISIBILITY_FILTER); args.add(user.departmentId()); args.add(user.departmentId()); }
    if(category!=null&&!category.isBlank()){ sql.append(" AND a.category=?"); args.add(category); }
    if(tag!=null&&!tag.isBlank()){ sql.append(" AND EXISTS (SELECT 1 FROM article_tags at2 JOIN knowledge_tags t ON t.id=at2.tag_id WHERE at2.article_id=a.id AND lower(t.name)=lower(?))"); args.add(tag); }
    sql.append(" AND c.search_vector @@ to_tsquery('simple',").append(fn).append("(?))");
    args.add(term);
    return sql.toString();
  }

  private long ftsCount(String term, UserContext user, String category, String tag, boolean orMode){
    String fn=ftsFn(orMode);
    List<Object> args=new ArrayList<>();
    String base=chunkFilterSql(term,user,category,tag,fn,args);
    String dedup="SELECT b.*,row_number() OVER (PARTITION BY b.article_id ORDER BY b.rank DESC, b.chunk_id) AS rn FROM ("+base+") b";
    Long total=jdbc.queryForObject("SELECT count(*) FROM ("+dedup+") d WHERE d.rn<="+MAX_HITS_PER_ARTICLE,Long.class,args.toArray());
    return total==null?0:total;
  }

  /** FTS 检索（窗口函数按文章去重后分页；headline 片段在去重后计算以减少开销）。 */
  private Page<KnowledgeHit> ftsHits(String term, UserContext user, String category, String tag, boolean orMode, int page, int size){
    String fn=ftsFn(orMode);
    List<Object> where=new ArrayList<>();
    String base=chunkFilterSql(term,user,category,tag,fn,where);
    String dedup="SELECT b.*,row_number() OVER (PARTITION BY b.article_id ORDER BY b.rank DESC, b.chunk_id) AS rn FROM ("+base+") b";
    Long total=jdbc.queryForObject("SELECT count(*) FROM ("+dedup+") d WHERE d.rn<="+MAX_HITS_PER_ARTICLE,Long.class,where.toArray());
    String sql="SELECT d.article_id,d.version_id,d.chunk_id,d.chunk_index,d.title,d.heading_path, "+
        "ts_headline('simple',d.content,to_tsquery('simple',"+fn+"(?))) AS snippet, d.rank, d.page_no "+
        "FROM ("+dedup+") d WHERE d.rn<="+MAX_HITS_PER_ARTICLE+" ORDER BY d.rank DESC, d.chunk_id LIMIT ? OFFSET ?";
    List<Object> args=new ArrayList<>(); args.add(term);
    args.addAll(where); args.add(size); args.add((page-1)*size);
    List<KnowledgeHit> items=jdbc.query(sql,(rs,n)->hit(rs,n),args.toArray());
    return new Page<>(items,total==null?0:total);
  }

  /** 向量召回：cosine 最近邻分块（同可见性/过滤条件），score=1/(1+距离) 便于与 ts_rank 同数量级展示。 */
  private List<KnowledgeHit> vectorHits(float[] qv, UserContext user, String category, String tag, int limit){
    StringBuilder sql=new StringBuilder(
        "SELECT a.id, v.id, c.id, c.chunk_index, a.title, c.heading_path, left(c.content,180) AS snippet, "+
        "1.0/(1.0+(c.embedding <=> ?::vector)) AS rank, c.page_no "+
        "FROM knowledge_chunks c JOIN knowledge_articles a ON a.id=c.article_id JOIN knowledge_versions v ON v.id=c.version_id "+
        "WHERE a.status='PUBLISHED' AND v.id=a.current_version_id AND c.embedding IS NOT NULL");
    List<Object> args=new ArrayList<>(); String literal=vectorLiteral(qv); args.add(literal);
    if(!user.admin()){ sql.append(VISIBILITY_FILTER); args.add(user.departmentId()); args.add(user.departmentId()); }
    if(category!=null&&!category.isBlank()){ sql.append(" AND a.category=?"); args.add(category); }
    if(tag!=null&&!tag.isBlank()){ sql.append(" AND EXISTS (SELECT 1 FROM article_tags at2 JOIN knowledge_tags t ON t.id=at2.tag_id WHERE at2.article_id=a.id AND lower(t.name)=lower(?))"); args.add(tag); }
    sql.append(" ORDER BY c.embedding <=> ?::vector LIMIT ?");
    args.add(literal); args.add(limit);
    return jdbc.query(sql.toString(),(rs,n)->hit(rs,n),args.toArray());
  }

  private static String vectorLiteral(float[] v){
    StringBuilder sb=new StringBuilder("[");
    for(int i=0;i<v.length;i++){ if(i>0) sb.append(','); sb.append(v[i]); }
    return sb.append(']').toString();
  }

  /** 浏览页字典：可见文章的分类列表（含未发布过滤与可见性过滤）。 */
  public List<String> categories(UserContext user){
    String vis=user.admin()?"":VISIBILITY_FILTER;
    List<Object> args=new ArrayList<>();
    if(!user.admin()){ args.add(user.departmentId()); args.add(user.departmentId()); }
    return jdbc.query("SELECT DISTINCT a.category FROM knowledge_articles a WHERE a.status='PUBLISHED'"+vis+" ORDER BY 1",(rs,n)->rs.getString(1),args.toArray());
  }

  /** 浏览页字典：可见文章的标签与文章数。 */
  public List<Map<String,Object>> tags(UserContext user){
    String vis=user.admin()?"":VISIBILITY_FILTER;
    List<Object> args=new ArrayList<>();
    if(!user.admin()){ args.add(user.departmentId()); args.add(user.departmentId()); }
    return jdbc.queryForList("SELECT t.name AS \"name\",count(DISTINCT a.id) AS \"count\" FROM knowledge_tags t "+
        "JOIN article_tags at2 ON at2.tag_id=t.id JOIN knowledge_articles a ON a.id=at2.article_id "+
        "WHERE a.status='PUBLISHED'"+vis+" GROUP BY t.name ORDER BY t.name",args.toArray());
  }

  private KnowledgeHit hit(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
    long articleId=rs.getLong(1); int versionId=rs.getInt(2); long chunkId=rs.getLong(3); int chunkIndex=rs.getInt(4);
    String citation="article:"+articleId+"/version:"+versionId+"#c"+chunkIndex;
    return new KnowledgeHit(articleId,versionId,rs.getString(5),rs.getString(7),rs.getDouble(8),citation,chunkId,chunkIndex,rs.getString(6),(Integer)rs.getObject(9));
  }

  /** 校验文章对当前用户可读（管理员全量；其他人仅 PUBLISHED 且（PUBLIC 或有部门授权），CONFIDENTIAL 必须部门授权）。 */
  private void requireReadable(long id, UserContext user){
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT a.status,a.visibility,a.sensitivity FROM knowledge_articles a WHERE a.id=?",id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文章不存在");
    if(user.admin()) return;
    Map<String,Object> row=rows.get(0);
    boolean published="PUBLISHED".equals(row.get("status"));
    boolean departmentGranted=user.departmentId()!=null&&jdbc.queryForObject("SELECT count(*) FROM knowledge_department_access WHERE article_id=? AND department_id=?",Integer.class,id,user.departmentId())>0;
    boolean allowed=("PUBLIC".equals(row.get("visibility"))||departmentGranted)
        && (!"CONFIDENTIAL".equals(row.get("sensitivity"))||departmentGranted);
    if(!published||!allowed) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文章不存在或无权访问");
  }

  public Map<String,Object> detail(long id, UserContext user, Long versionId){
    requireReadable(id,user);
    Map<String,Object> article=jdbc.queryForList("SELECT a.id,a.title,a.category,a.status,a.visibility,a.reviewed_at AS \"reviewedAt\",a.review_comment AS \"reviewComment\",a.sensitivity,a.source_note_id AS \"sourceNoteId\",a.created_at AS \"createdAt\",a.updated_at AS \"updatedAt\",a.current_version_id AS \"currentVersionId\" FROM knowledge_articles a WHERE a.id=?",id).get(0);
    Number current=(Number)article.get("currentVersionId");
    if(current==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"文章尚无可用版本");
    long vid=versionId!=null?versionId:current.longValue();
    Map<String,Object> result=new LinkedHashMap<>(article);
    List<Map<String,Object>> versionRows=jdbc.queryForList("SELECT id,version,status,chunk_ready,content,created_at FROM knowledge_versions WHERE id=? AND article_id=?",vid,id);
    if(versionRows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"版本不存在或不属于该文章");
    Map<String,Object> version=versionRows.get(0);
    result.put("versionId",version.get("id")); result.put("version",version.get("version")); result.put("versionStatus",version.get("status"));
    result.put("chunkReady",version.get("chunk_ready")); result.put("content",version.get("content"));
    result.put("tags",jdbc.queryForList("SELECT t.name FROM article_tags at2 JOIN knowledge_tags t ON t.id=at2.tag_id WHERE at2.article_id=? ORDER BY t.name",id));
    if(user.admin()) result.put("departments",jdbc.queryForList("SELECT d.id,d.name FROM knowledge_department_access da JOIN departments d ON d.id=da.department_id WHERE da.article_id=?",id));
    result.put("chunks",jdbc.query("SELECT id,chunk_index,heading_path,content,page_no,char_count FROM knowledge_chunks WHERE version_id=? ORDER BY chunk_index",(rs,n)->new ChunkInfo(rs.getLong(1),rs.getInt(2),rs.getString(3),rs.getString(4),(Integer)rs.getObject(5),(Integer)rs.getObject(6)),vid));
    List<Map<String,Object>> files=jdbc.queryForList("SELECT file_name AS \"fileName\",size_bytes AS \"sizeBytes\" FROM import_items WHERE article_id=? ORDER BY id DESC LIMIT 1",id);
    result.put("file",files.isEmpty()?null:files.get(0));
    return result;
  }

  public List<ArticleVersionInfo> versions(long id, UserContext user){
    requireReadable(id,user);
    return jdbc.query("SELECT v.id,v.version,v.status,v.chunk_ready,length(v.content),u.display_name,v.created_at,(v.id=a.current_version_id) FROM knowledge_versions v JOIN knowledge_articles a ON a.id=v.article_id LEFT JOIN users u ON u.id=v.created_by WHERE v.article_id=? ORDER BY v.version DESC",
        (rs,n)->new ArticleVersionInfo(rs.getLong(1),rs.getInt(2),rs.getString(3),rs.getBoolean(4),rs.getBoolean(8),rs.getLong(5),rs.getString(6),rs.getTimestamp(7)==null?null:rs.getTimestamp(7).toInstant()),id);
  }

  public List<ChunkInfo> chunks(long id, UserContext user){
    requireReadable(id,user);
    return jdbc.query("SELECT c.id,c.chunk_index,c.heading_path,c.content,c.page_no,c.char_count FROM knowledge_chunks c JOIN knowledge_articles a ON a.id=c.article_id WHERE c.article_id=? AND c.version_id=a.current_version_id ORDER BY c.chunk_index",
        (rs,n)->new ChunkInfo(rs.getLong(1),rs.getInt(2),rs.getString(3),rs.getString(4),(Integer)rs.getObject(5),(Integer)rs.getObject(6)),id);
  }

  /** 原文件（导入原件）来源信息：articleId -> 最近一次成功导入的文件。 */
  public Map<String,Object> fileSource(long id, UserContext user){
    requireReadable(id,user);
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT object_key AS \"objectKey\",file_name AS \"fileName\",content_type AS \"contentType\" FROM import_items WHERE article_id=? AND status<>'FAILED' ORDER BY id DESC LIMIT 1",id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"该文章没有可下载的原始文件");
    return rows.get(0);
  }

  public KnowledgeArticle publish(long id, UserContext user){ return review(id,true,null,user); }

  /** 审核状态机（事务）：approve=true 时最新版本置 PUBLISHED 并切换生效，旧版本归档；false 时文章置 REJECTED。 */
  public KnowledgeArticle review(long id, boolean approve, String comment, UserContext user){
    auth.requireAdmin(user);
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,status,current_version_id FROM knowledge_articles WHERE id=?",id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文章不存在");
    // 发布对象是“最新版本”：同文件再导入的版本策略会先生成 DRAFT vN+1，审核通过后发布并切换生效
    List<Long> latest=jdbc.query("SELECT id FROM knowledge_versions WHERE article_id=? ORDER BY version DESC LIMIT 1",(rs,n)->rs.getLong(1),id);
    if(latest.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"文章尚无可用版本");
    long versionId=latest.get(0);
    tx.executeWithoutResult(s -> {
      if(approve){
        jdbc.update("UPDATE knowledge_versions SET status='PUBLISHED' WHERE id=?",versionId);
        jdbc.update("UPDATE knowledge_versions SET status='ARCHIVED' WHERE article_id=? AND id<>? AND status='PUBLISHED'",id,versionId);
        // 关键：切换生效版本。此前只改状态不切换 current_version_id，导致导入"作为新版本导入"
        // 与在线编辑生成的新版本审核通过后永远不会对员工生效（detail 仍返回旧版本内容）
        jdbc.update("UPDATE knowledge_articles SET status='PUBLISHED',reviewed_by=?,reviewed_at=now(),review_comment=?,current_version_id=?,updated_at=now() WHERE id=?",user.id(),comment,versionId,id);
        jdbc.update("UPDATE import_items SET status='PUBLISHED',updated_at=now() WHERE article_id=? AND status='REVIEW'",id);
      } else {
        jdbc.update("UPDATE knowledge_articles SET status='REJECTED',reviewed_by=?,reviewed_at=now(),review_comment=?,updated_at=now() WHERE id=?",user.id(),comment,id);
        jdbc.update("UPDATE import_items SET status='REJECTED',updated_at=now() WHERE article_id=? AND status='REVIEW'",id);
      }
    });
    audit.log(user.id(),"KNOWLEDGE_REVIEWED","KNOWLEDGE",id,Map.of("approved",approve,"comment",Objects.toString(comment,"")));
    if(approve) audit.log(user.id(),"KNOWLEDGE_VERSION_PUBLISHED","KNOWLEDGE_VERSION",versionId,Map.of("articleId",id));
    notifyAuthor(id, versionId, approve, user);
    return list(user).stream().filter(a->a.id()==id).findFirst().orElseThrow();
  }

  /** 审核结果触达文章作者（导入生成等无作者的文章自动跳过）。 */
  private void notifyAuthor(long articleId, long versionId, boolean approved, UserContext reviewer) {
    List<Long> authors = jdbc.query("SELECT created_by FROM knowledge_versions WHERE id=? AND created_by IS NOT NULL", (rs,n)->rs.getLong(1), versionId);
    if (authors.isEmpty() || authors.get(0) == reviewer.id()) return;
    String title = jdbc.queryForObject("SELECT title FROM knowledge_articles WHERE id=?", String.class, articleId);
    notifications.notify(authors.get(0), approved ? "KNOWLEDGE_PUBLISHED" : "KNOWLEDGE_REJECTED",
        approved ? "你的知识文章《" + title + "》已通过审核并发布" : "你的知识文章《" + title + "》未通过审核",
        "/knowledge/articles/" + articleId, "KNOWLEDGE", articleId, null);
  }

  /** 撤回/下架（事务）：文章置 ARCHIVED 后立即退出检索与列表，保留全部版本与审计记录，不做物理删除。 */
  public KnowledgeArticle retract(long id, UserContext user){
    auth.requireAdmin(user);
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,status FROM knowledge_articles WHERE id=?",id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文章不存在");
    tx.executeWithoutResult(s -> {
      jdbc.update("UPDATE knowledge_articles SET status='ARCHIVED',reviewed_by=?,reviewed_at=now(),review_comment=?,updated_at=now() WHERE id=?",user.id(),"管理员撤回",id);
      jdbc.update("UPDATE import_items SET status='RETRACTED',updated_at=now() WHERE article_id=? AND status='PUBLISHED'",id);
    });
    audit.log(user.id(),"KNOWLEDGE_RETRACTED","KNOWLEDGE",id,Map.of("previousStatus",Objects.toString(rows.get(0).get("status"),"")));
    return list(user).stream().filter(a->a.id()==id).findFirst().orElseThrow();
  }

  /** 管理员编辑文章：内容变更生成新版本（vMax+1，DRAFT）并重新分块，文章回到 IN_REVIEW 走既有审核流；
   *  current_version_id 不切换——审核通过前，线上发布版本保持可读。仅元数据变更不影响状态。 */
  public Map<String,Object> update(long id, KnowledgeUpdateRequest req, UserContext user){
    auth.requireAdmin(user);
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,status FROM knowledge_articles WHERE id=?",id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文章不存在");
    String sensitivity=req.sensitivity()==null?null:req.sensitivity().toUpperCase(java.util.Locale.ROOT);
    if(sensitivity!=null&&!Set.of("PUBLIC","INTERNAL","CONFIDENTIAL").contains(sensitivity)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"密级不合法");
    boolean contentChanged=req.content()!=null&&!req.content().isBlank();
    int newVersion=0; long newVersionId=0;
    if(contentChanged){
      newVersion=jdbc.queryForObject("SELECT coalesce(max(version),0)+1 FROM knowledge_versions WHERE article_id=?",Integer.class,id);
      newVersionId=jdbc.queryForObject("INSERT INTO knowledge_versions(article_id,version,content,created_by,status) VALUES (?,?,?,?,'DRAFT') RETURNING id",Long.class,id,newVersion,req.content(),user.id());
      indexChunks(id,newVersionId,req.content());
      embedChunksIfConfigured(newVersionId);
    }
    final boolean contentFlag=contentChanged;
    tx.executeWithoutResult(s -> {
      jdbc.update("UPDATE knowledge_articles SET title=COALESCE(?,title),category=COALESCE(?,category),visibility=COALESCE(?,visibility),"+
          "sensitivity=COALESCE(?,sensitivity),status=CASE WHEN ? THEN 'IN_REVIEW' ELSE status END,updated_at=now() WHERE id=?",
          req.title(),req.category(),req.visibility(),sensitivity,contentFlag,id);
      if(req.tags()!=null){
        jdbc.update("DELETE FROM article_tags WHERE article_id=?",id);
        for(String tag:req.tags()) if(tag!=null&&!tag.isBlank()){ jdbc.update("INSERT INTO knowledge_tags(name) VALUES (?) ON CONFLICT DO NOTHING",tag.trim()); Long tagId=jdbc.queryForObject("SELECT id FROM knowledge_tags WHERE name=?",Long.class,tag.trim()); jdbc.update("INSERT INTO article_tags(article_id,tag_id) VALUES (?,?) ON CONFLICT DO NOTHING",id,tagId); }
      }
      if(req.departmentIds()!=null){
        jdbc.update("DELETE FROM knowledge_department_access WHERE article_id=?",id);
        for(Long departmentId:req.departmentIds()) if(departmentId!=null) jdbc.update("INSERT INTO knowledge_department_access(article_id,department_id) VALUES (?,?) ON CONFLICT DO NOTHING",id,departmentId);
      }
    });
    audit.log(user.id(),"KNOWLEDGE_UPDATED","KNOWLEDGE",id,Map.of("contentChanged",contentChanged,"newVersion",newVersion));
    KnowledgeArticle article=list(user).stream().filter(a->a.id()==id).findFirst().orElseThrow();
    return Map.of("article",article,"newVersionId",newVersionId,"newVersion",newVersion,"contentChanged",contentChanged);
  }

  public KnowledgeArticle create(KnowledgeCreateRequest req, UserContext user){
    auth.requireAdmin(user); String visibility=req.visibility()==null?"PUBLIC":req.visibility();
    String sensitivity=Objects.toString(req.sensitivity(),"INTERNAL").toUpperCase(java.util.Locale.ROOT);
    if(!Set.of("PUBLIC","INTERNAL","CONFIDENTIAL").contains(sensitivity)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"密级不合法");
    Long article=tx.execute(s -> {
      Long articleId=jdbc.queryForObject("INSERT INTO knowledge_articles(title,category,status,visibility,sensitivity) VALUES (?,?, 'DRAFT',?,?) RETURNING id",Long.class,req.title(),Objects.toString(req.category(),"GENERAL"),visibility,sensitivity);
      Long version=jdbc.queryForObject("INSERT INTO knowledge_versions(article_id,version,content,created_by) VALUES (?,1,?,?) RETURNING id",Long.class,articleId,req.content(),user.id());
      jdbc.update("UPDATE knowledge_articles SET current_version_id=?,updated_at=now() WHERE id=?",version,articleId);
      indexChunks(articleId,version,req.content());
      if (req.tags() != null) for (String tag : req.tags()) if (tag != null && !tag.isBlank()) { jdbc.update("INSERT INTO knowledge_tags(name) VALUES (?) ON CONFLICT DO NOTHING", tag.trim()); Long tagId = jdbc.queryForObject("SELECT id FROM knowledge_tags WHERE name=?", Long.class, tag.trim()); jdbc.update("INSERT INTO article_tags(article_id,tag_id) VALUES (?,?) ON CONFLICT DO NOTHING", articleId, tagId); }
      if (req.departmentIds() != null) for (Long departmentId : req.departmentIds()) if (departmentId != null) jdbc.update("INSERT INTO knowledge_department_access(article_id,department_id) VALUES (?,?) ON CONFLICT DO NOTHING", articleId, departmentId);
      return articleId;
    });
    final long createdArticle=article;
    final long createdVersion=jdbc.queryForObject("SELECT current_version_id FROM knowledge_articles WHERE id=?",Long.class,createdArticle);
    audit.log(user.id(),"KNOWLEDGE_CREATED","KNOWLEDGE",article,Map.of());
    java.util.concurrent.CompletableFuture.runAsync(() -> embedChunksIfConfigured(createdVersion));
    return list(user).stream().filter(a->a.id()==article).findFirst().orElseThrow();
  }
}
