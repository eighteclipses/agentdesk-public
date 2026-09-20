package com.agentdesk.service;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 个人知识库（Vault）：服务端为数据主体，Obsidian 是可选同步客户端（P2）。
 * 个人笔记与企业知识物理隔离在不同的表与索引；"提交审核"才把内容复制为企业草稿并带 source_note_id 溯源。
 */
@Service
public class VaultService {
  private final JdbcTemplate jdbc; private final AuditService audit; private final NotificationService notifications;
  public VaultService(JdbcTemplate jdbc, AuditService audit, NotificationService notifications) { this.jdbc=jdbc; this.audit=audit; this.notifications=notifications; }

  private static final Pattern WIKILINK=Pattern.compile("\\[\\[([^\\]\\[|#]+?)(?:#([^\\]\\[|]+?))?(?:\\|([^\\]\\[|]+?))?\\]\\]");

  // ---------- 笔记 CRUD ----------

  private long defaultVault(long userId) {
    List<Long> ids=jdbc.query("SELECT id FROM vaults WHERE owner_id=? ORDER BY id LIMIT 1",(rs,n)->rs.getLong(1),userId);
    if (!ids.isEmpty()) return ids.get(0);
    return jdbc.queryForObject("INSERT INTO vaults(owner_id,name) VALUES (?,'我的笔记') RETURNING id",Long.class,userId);
  }

  public Note create(NoteUpsertRequest req, UserContext user) {
    String title=Objects.toString(req.title(),"").strip();
    if (title.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"笔记标题不能为空");
    String content=req.content()==null?"":req.content();
    List<String> tags=normalizeTags(req.tags());
    long vaultId=defaultVault(user.id());
    long id=jdbc.queryForObject("INSERT INTO notes(vault_id,folder_path,title,content,tags,content_hash,created_by) VALUES (?,'',?,?,?,?,?) RETURNING id",
        Long.class,vaultId,title,content,tags,sha256(content),user.id());
    jdbc.update("INSERT INTO note_versions(note_id,version,content) VALUES (?,1,?)",id,content);
    resolveLinks(id,content,user.id());
    audit.log(user.id(),"NOTE_CREATED","NOTE",id,Map.of());
    return get(id,user);
  }

  public Note update(long id, NoteUpsertRequest req, UserContext user) {
    Map<String,Object> note=ownedNote(id,user);
    String title=req.title()==null?Objects.toString(note.get("title"),""):req.title().strip();
    String content=req.content()==null?Objects.toString(note.get("content"),""):req.content();
    List<String> tags=req.tags()==null?readTags(note):normalizeTags(req.tags());
    String hash=sha256(content);
    String oldHash=Objects.toString(note.get("content_hash"),"");
    if (!oldHash.equals(hash)) {
      long next=jdbc.queryForObject("SELECT coalesce(max(version),0)+1 FROM note_versions WHERE note_id=?",Long.class,id);
      jdbc.update("INSERT INTO note_versions(note_id,version,content) VALUES (?,?,?)",id,next,content);
    }
    jdbc.update("UPDATE notes SET title=?,content=?,tags=?,content_hash=?,updated_at=now() WHERE id=?",title,content,tags,hash,id);
    resolveLinks(id,content,user.id());
    audit.log(user.id(),"NOTE_UPDATED","NOTE",id,Map.of("hashChanged",!oldHash.equals(hash)));
    return get(id,user);
  }

  public void delete(long id, UserContext user) {
    ownedNote(id,user);
    jdbc.update("DELETE FROM notes WHERE id=?",id);
    audit.log(user.id(),"NOTE_DELETED","NOTE",id,Map.of());
  }

  public List<Map<String,Object>> list(UserContext user, String folder, String q) {
    String sql="SELECT n.id,n.title,n.folder_path,n.tags,n.status,n.updated_at,(SELECT version FROM note_versions nv WHERE nv.note_id=n.id ORDER BY version DESC LIMIT 1) AS version FROM notes n JOIN vaults v ON v.id=n.vault_id WHERE v.owner_id=?";
    List<Object> args=new ArrayList<>(); args.add(user.id());
    if (folder!=null&&!folder.isBlank()) { sql+=" AND n.folder_path=?"; args.add(folder); }
    if (q!=null&&!q.isBlank()) { sql+=" AND n.search_vector @@ to_tsquery('simple',agentdesk_fts_query_tokens(?))"; q=q.strip(); args.add(q); }
    sql+=" ORDER BY n.updated_at DESC";
    // TEXT[] 列手工映射为 List，避免 Jackson 直接序列化 PgArray
    return jdbc.query(sql,(rs,n)->{
      Map<String,Object> row=new LinkedHashMap<>();
      row.put("id",rs.getLong(1)); row.put("title",rs.getString(2)); row.put("folderPath",rs.getString(3));
      row.put("folder_path",rs.getString(3));
      row.put("tags",toList(rs.getArray(4))); row.put("status",rs.getString(5));
      row.put("updatedAt",rs.getTimestamp(6).toInstant());
      row.put("version",rs.getInt(7));
      return row;
    },args.toArray());
  }

  private List<String> toList(java.sql.Array arr) {
    if (arr==null) return List.of();
    try { Object[] raw=(Object[])arr.getArray(); List<String> out=new ArrayList<>(); for (Object t:raw) out.add(Objects.toString(t,"")); return out; }
    catch (Exception e) { return List.of(); }
  }

  public Map<String,Object> detail(long id, UserContext user) {
    Map<String,Object> note=ownedNote(id,user);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("id",note.get("id")); result.put("title",note.get("title"));
    result.put("folderPath",note.get("folder_path"));
    result.put("content",note.get("content"));
    result.put("status",note.get("status"));
    result.put("contentHash",note.get("content_hash"));
    result.put("createdAt",note.get("created_at")==null?null:((java.sql.Timestamp)note.get("created_at")).toInstant());
    result.put("updatedAt",note.get("updated_at")==null?null:((java.sql.Timestamp)note.get("updated_at")).toInstant());
    result.put("tags",readTags(note));
    result.put("outgoing",jdbc.queryForList("SELECT raw_target AS \"rawTarget\",dst_note_id AS \"dstNoteId\",anchor,resolved,dst.title AS \"dstTitle\" FROM note_links l LEFT JOIN notes dst ON dst.id=l.dst_note_id WHERE l.src_note_id=?",id));
    result.put("backlinks",jdbc.queryForList("SELECT l.src_note_id AS \"srcNoteId\",s.title AS \"srcTitle\" FROM note_links l JOIN notes s ON s.id=l.src_note_id WHERE l.dst_note_id=? ORDER BY l.id",id));
    result.put("versions",jdbc.queryForList("SELECT version,length(content) AS characters,created_at FROM note_versions WHERE note_id=? ORDER BY version DESC",id));
    return result;
  }

  private Map<String,Object> ownedNote(long id, UserContext user) {
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT n.* FROM notes n JOIN vaults v ON v.id=n.vault_id WHERE n.id=? AND v.owner_id=?",id,user.id());
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"笔记不存在");
    return rows.get(0);
  }

  public Note get(long id, UserContext user) {
    Map<String,Object> row=ownedNote(id,user);
    int version=jdbc.queryForObject("SELECT coalesce(max(version),1) FROM note_versions WHERE note_id=?",Integer.class,id);
    return new Note(id,Objects.toString(row.get("title"),""),Objects.toString(row.get("folder_path"),""),
        Objects.toString(row.get("content"),""),readTags(row),Objects.toString(row.get("status"),"DRAFT"),version,
        ((java.sql.Timestamp)row.get("updated_at")).toInstant());
  }

  @SuppressWarnings("unchecked")
  private List<String> readTags(Map<String,Object> row) {
    Object tags=row.get("tags");
    if (tags instanceof List<?> list) { List<String> out=new ArrayList<>(); list.forEach(t->out.add(Objects.toString(t,""))); return out; }
    if (tags instanceof java.sql.Array arr) { try { Object[] raw=(Object[])arr.getArray(); List<String> out=new ArrayList<>(); for (Object t:raw) out.add(Objects.toString(t,"")); return out; } catch (Exception e) { return List.of(); } }
    return List.of();
  }

  private List<String> normalizeTags(List<String> tags) {
    if (tags==null) return List.of();
    Set<String> seen=new LinkedHashSet<>();
    for (String t : tags) if (t!=null&&!t.isBlank()) seen.add(t.trim());
    return new ArrayList<>(seen);
  }

  // ---------- 双向链接 ----------

  /** 解析 [[标题#锚点|别名]]：按标题或别名解析目标笔记；未命中的链接保留 raw_target 并在图谱中显示为未解析。 */
  private void resolveLinks(long noteId, String content, long ownerId) {
    jdbc.update("DELETE FROM note_links WHERE src_note_id=?",noteId);
    Matcher m=WIKILINK.matcher(content);
    Set<String> seen=new HashSet<>();
    while (m.find()) {
      String target=m.group(1)==null?"":m.group(1).strip();
      if (target.isBlank()) continue;
      String raw=m.group(0);
      if (!seen.add(raw)) continue;
      Long dst=findNoteByTitleOrAlias(target,ownerId);
      jdbc.update("INSERT INTO note_links(src_note_id,raw_target,dst_note_id,anchor,resolved) VALUES (?,?,?,?,?)",
          noteId,raw,dst,m.group(2),dst!=null);
    }
  }

  private Long findNoteByTitleOrAlias(String title, long ownerId) {
    List<Long> ids=jdbc.query("SELECT n.id FROM notes n JOIN vaults v ON v.id=n.vault_id LEFT JOIN note_aliases na ON na.note_id=n.id WHERE v.owner_id=? AND (lower(n.title)=lower(?) OR lower(na.alias)=lower(?)) LIMIT 1",
        (rs,n)->rs.getLong(1),ownerId,title,title);
    return ids.isEmpty()?null:ids.get(0);
  }

  // ---------- 个人检索（物理隔离于企业 knowledge_chunks） ----------

  public List<KnowledgeHit> searchNotes(String term, UserContext user) {
    if (term==null||term.isBlank()) return List.of();
    // 与企业检索同一套 CJK 分词函数；无需 LIKE 兜底
    String q="SELECT n.id,n.title,ts_headline('simple',n.content,to_tsquery('simple',agentdesk_fts_query_tokens(?))),ts_rank(n.search_vector,to_tsquery('simple',agentdesk_fts_query_tokens(?))) FROM notes n JOIN vaults v ON v.id=n.vault_id WHERE v.owner_id=? AND n.search_vector @@ to_tsquery('simple',agentdesk_fts_query_tokens(?)) ORDER BY 4 DESC LIMIT 5";
    return jdbc.query(q,(rs,n)->new KnowledgeHit(rs.getLong(1),0,rs.getString(2),rs.getString(3),rs.getDouble(4),"note:"+rs.getLong(1),null,null,null,null),term,term,user.id(),term);
  }

  // ---------- 过滤“个人内容进入企业检索”的转换 ----------

  /** 提交审核：内容复制为企业知识草稿（IN_REVIEW），source_note_id 溯源；发布前不参与员工问答。 */
  public Map<String,Object> toEnterprise(long noteId, UserContext user) {
    Map<String,Object> note=ownedNote(noteId,user);
    String title=Objects.toString(note.get("title"),"未命名笔记");
    String content=Objects.toString(note.get("content"),"");
    Long articleId=jdbc.queryForObject("INSERT INTO knowledge_articles(title,category,status,visibility,source_note_id) VALUES (?,?, 'IN_REVIEW','PUBLIC',?) RETURNING id",
        Long.class,title,"PERSONAL",noteId);
    String body="> 来源：个人笔记 #"+noteId+"（"+user.displayName()+" 提交审核）\n\n"+content;
    Long versionId=jdbc.queryForObject("INSERT INTO knowledge_versions(article_id,version,content,created_by,status) VALUES (?,1,?,?,'DRAFT') RETURNING id",
        Long.class,articleId,body,user.id());
    jdbc.update("UPDATE knowledge_articles SET current_version_id=? WHERE id=?",versionId,articleId);
    audit.log(user.id(),"NOTE_TO_ENTERPRISE","NOTE",noteId,Map.of("articleId",articleId));
    List<Long> admins=jdbc.query("SELECT u.id FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE r.code='ADMIN' AND u.active=true AND u.account_status='ACTIVE'",(rs,n)->rs.getLong(1));
    notifications.notifyAll(admins,"KNOWLEDGE_REVIEW_REQUESTED","员工 "+user.displayName()+" 提交了知识文章《"+title+"》待审核","/admin/knowledge","KNOWLEDGE",articleId,null);
    return Map.of("articleId",articleId,"title",title,"status","IN_REVIEW");
  }

  // ---------- 图谱 ----------

  public Map<String,Object> graph(UserContext user, String scope, Long ref) {
    List<Map<String,Object>> nodes=new ArrayList<>();
    List<Map<String,Object>> edges=new ArrayList<>();
    jdbc.query("SELECT n.id,n.title,n.folder_path FROM notes n JOIN vaults v ON v.id=n.vault_id WHERE v.owner_id=?",(rs,n)->{
      nodes.add(node("note:"+rs.getLong(1),"NOTE",rs.getString(2),rs.getString(3))); return null; },user.id());
    jdbc.query("SELECT DISTINCT unnest(n.tags) AS tag FROM notes n JOIN vaults v ON v.id=n.vault_id WHERE v.owner_id=?",(rs,n)->{
      nodes.add(node("tag:"+rs.getString(1),"TAG",rs.getString(1),"")); return null; },user.id());
    jdbc.query("SELECT id,title FROM knowledge_articles WHERE status='PUBLISHED' AND (visibility='PUBLIC' OR ?=true OR EXISTS (SELECT 1 FROM knowledge_department_access da WHERE da.article_id=knowledge_articles.id AND da.department_id=?)) ORDER BY updated_at DESC LIMIT 50",
        (rs,n)->{ nodes.add(node("article:"+rs.getLong(1),"ARTICLE",rs.getString(2),"")); return null; },user.admin(),user.departmentId());
    jdbc.query("SELECT l.src_note_id,l.dst_note_id FROM note_links l WHERE l.resolved=true AND l.dst_note_id IS NOT NULL AND EXISTS (SELECT 1 FROM notes n JOIN vaults v ON v.id=n.vault_id WHERE n.id=l.src_note_id AND v.owner_id=?)",
        (rs,n)->{ edges.add(edge("note:"+rs.getLong(1),"note:"+rs.getLong(2),"link",true)); return null; },user.id());
    jdbc.query("SELECT n.id,unnest(n.tags) AS tag FROM notes n JOIN vaults v ON v.id=n.vault_id WHERE v.owner_id=?",
        (rs,n)->{ edges.add(edge("note:"+rs.getLong(1),"tag:"+rs.getString(2),"tag",true)); return null; },user.id());
    jdbc.query("SELECT t.name,at.article_id FROM article_tags at JOIN knowledge_tags t ON t.id=at.tag_id JOIN knowledge_articles a ON a.id=at.article_id WHERE a.status='PUBLISHED' AND (a.visibility='PUBLIC' OR ?=true OR EXISTS (SELECT 1 FROM knowledge_department_access da WHERE da.article_id=a.id AND da.department_id=?)) ORDER BY at.article_id LIMIT 120",
        (rs,n)->{ edges.add(edge("article:"+rs.getLong(2),"tag:"+rs.getString(1),"tag",true)); return null; },user.admin(),user.departmentId());
    if ("note".equalsIgnoreCase(scope)&&ref!=null) pruneToSubgraph(nodes,edges,ref);
    return Map.of("nodes",nodes,"edges",edges);
  }

  /** 局部图：保留 ref 笔记与其一跳邻居及之间的边。 */
  private void pruneToSubgraph(List<Map<String,Object>> nodes,List<Map<String,Object>> edges,long ref) {
    String key="note:"+ref;
    Set<String>keepers=new HashSet<>();
    keepers.add(key);
    for (Map<String,Object> e : edges) {
      String src=Objects.toString(e.get("source")); String dst=Objects.toString(e.get("target"));
      if (src.equals(key)) keepers.add(dst);
      if (dst.equals(key)) keepers.add(src);
    }
    nodes.removeIf(n->!keepers.contains(n.get("key")));
    edges.removeIf(e->!keepers.contains(e.get("source"))||!keepers.contains(e.get("target")));
  }

  private Map<String,Object> node(String key,String type,String label,String folder) {
    Map<String,Object> m=new LinkedHashMap<>(); m.put("key",key); m.put("type",type); m.put("label",label); m.put("folder",folder); return m;
  }
  private Map<String,Object> edge(String source,String target,String kind,boolean resolved) {
    Map<String,Object> m=new LinkedHashMap<>(); m.put("source",source); m.put("target",target); m.put("kind",kind); m.put("resolved",resolved); return m;
  }

  private String sha256(String text) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    catch (Exception e) { throw new IllegalStateException("哈希计算失败",e); }
  }
}