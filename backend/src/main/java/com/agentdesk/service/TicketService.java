package com.agentdesk.service;

import com.agentdesk.api.ApiModels;
import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class TicketService {
  private final JdbcTemplate jdbc; private final AuthContext auth; private final AuditService audit; private final StorageService storage; private final NotificationService notifications;
  private static final Map<String, Set<String>> TRANSITIONS = Map.of(
      "NEW", Set.of("TRIAGED", "IN_PROGRESS"), "TRIAGED", Set.of("ASSIGNED", "IN_PROGRESS"),
      "ASSIGNED", Set.of("IN_PROGRESS", "PENDING_USER"), "IN_PROGRESS", Set.of("PENDING_USER", "RESOLVED"),
      "PENDING_USER", Set.of("IN_PROGRESS", "RESOLVED"), "RESOLVED", Set.of("CLOSED", "IN_PROGRESS"), "CLOSED", Set.of());
  /** 请求人对已解决工单的自助动作：确认关闭或问题未解决时重开 */
  private static final Set<String> REQUESTER_TRANSITIONS = Set.of("CLOSED", "IN_PROGRESS");
  /** SLA 时限按优先级：越紧急越短 */
  private static final Map<String, Integer> SLA_HOURS = Map.of("P1", 4, "P2", 8, "P3", 24, "P4", 72);
  public TicketService(JdbcTemplate jdbc, AuthContext auth, AuditService audit, StorageService storage, NotificationService notifications) { this.jdbc=jdbc; this.auth=auth; this.audit=audit; this.storage=storage; this.notifications=notifications; }

  private static final String TICKET_SELECT = "SELECT t.*,d.name AS department_name,q.code AS queue_code,q.name AS queue_name,"
      + "ru.display_name AS requester_name,au.display_name AS assignee_name "
      + "FROM tickets t LEFT JOIN departments d ON d.id=t.department_id LEFT JOIN support_queues q ON q.id=t.queue_id "
      + "LEFT JOIN users ru ON ru.id=t.requester_id LEFT JOIN users au ON au.id=t.assignee_id";

  public Page<Ticket> list(UserContext user) { return list(user, null, null, null, null); }

  /** 分页工单列表；可按状态/优先级过滤；page/size 为 null 时返回全量（兼容旧调用方）。 */
  public Page<Ticket> list(UserContext user, Integer page, Integer size, String status, String priority) {
    return list(user, page, size, status, priority, null, null, null);
  }

  public Page<Ticket> list(UserContext user, Integer page, Integer size, String status, String priority, String query, String view, String sort) {
    StringBuilder where = new StringBuilder();
    List<Object> args = new ArrayList<>();
    if (!user.admin()) {
      if (user.agent()) {
        if (user.queueIds().isEmpty()) return new Page<>(List.of(), 0);
        where.append(" WHERE t.queue_id IN (").append(String.join(",", Collections.nCopies(user.queueIds().size(), "?"))).append(")");
        args.addAll(user.queueIds());
      } else { where.append(" WHERE t.requester_id=?"); args.add(user.id()); }
    }
    if (status!=null&&!status.isBlank()) { where.append(where.isEmpty() ? " WHERE" : " AND").append(" t.status=?"); args.add(status.toUpperCase()); }
    if (priority!=null&&!priority.isBlank()) { where.append(where.isEmpty() ? " WHERE" : " AND").append(" t.priority=?"); args.add(priority.toUpperCase()); }
    if (query != null && !query.isBlank()) {
      String q = query.trim();
      if (q.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索词不能超过 200 字");
      where.append(where.isEmpty() ? " WHERE" : " AND").append(" (strpos(lower(t.title),lower(?))>0 OR strpos(lower(t.description),lower(?))>0 OR t.id::text=?)");
      args.add(q); args.add(q); args.add(q.replaceFirst("^#", ""));
    }
    String filter = switch (Objects.toString(view, "")) {
      case "open" -> " t.status NOT IN ('RESOLVED','CLOSED')";
      case "mine" -> " t.assignee_id=? AND t.status NOT IN ('RESOLVED','CLOSED')";
      case "unassigned" -> " t.assignee_id IS NULL AND t.status NOT IN ('RESOLVED','CLOSED')";
      case "overdue" -> " t.due_at<now() AND t.status NOT IN ('RESOLVED','CLOSED')";
      default -> "";
    };
    if (!filter.isEmpty()) { where.append(where.isEmpty() ? " WHERE" : " AND").append(filter); if ("mine".equals(view)) args.add(user.id()); }
    Long total = jdbc.queryForObject("SELECT count(*) FROM tickets t " + where, Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    String order = "due".equals(sort) ? "t.due_at ASC NULLS LAST,t.id ASC" : "updated".equals(sort) ? "t.updated_at DESC,t.id DESC" : "t.created_at DESC,t.id DESC";
    String sql = TICKET_SELECT + where + " ORDER BY " + order;
    if (page != null || size != null) {
      int p = Math.max(1, page == null ? 1 : page); int s = Math.min(Math.max(1, size == null ? 20 : size), 100);
      sql += " LIMIT ? OFFSET ?"; pageArgs.add(s); pageArgs.add((long)(p - 1) * s);
    }
    return new Page<>(jdbc.query(sql, this::map, pageArgs.toArray()), total == null ? 0 : total);
  }

  public Ticket get(long id, UserContext user) {
    return get(id, user, false);
  }

  Ticket lock(long id, UserContext user) { return get(id,user,true); }

  private Ticket get(long id, UserContext user, boolean lock) {
    List<Ticket> rows = jdbc.query(TICKET_SELECT + " WHERE t.id=?" + (lock ? " FOR UPDATE OF t" : ""), this::map, id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
    Ticket t=rows.get(0); if (!user.agent() && t.requesterId()!=user.id()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问此工单"); if(user.agent()&&!user.admin()&&!user.queueIds().contains(t.queueId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前处理人未被授权此队列"); return t;
  }

  /** 该工单所属队列中可指派的处理人（激活的 AGENT 角色成员）。 */
  public List<AssigneeOption> assignees(long ticketId, UserContext user) {
    Ticket t = get(ticketId, user);
    return jdbc.query("SELECT u.id,u.username,u.display_name FROM queue_members qm JOIN users u ON u.id=qm.user_id "
        + "JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id "
        + "WHERE qm.queue_id=? AND u.active=true AND u.account_status='ACTIVE' AND r.code='AGENT' ORDER BY u.id",
        (rs,n)->new AssigneeOption(rs.getLong(1),rs.getString(2),rs.getString(3)), t.queueId());
  }

  @Transactional
  public Ticket create(TicketCreateRequest req, UserContext user) {
    String category = req.category()==null||req.category().isBlank()?"GENERAL":req.category().trim().toUpperCase(Locale.ROOT);
    String priority=req.priority()==null||req.priority().isBlank()?"P3":req.priority().trim().toUpperCase(Locale.ROOT);
    if (!SLA_HOURS.containsKey(priority)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优先级必须为 P1/P2/P3/P4");
    if (!Set.of("NETWORK","ACCESS","SOFTWARE","GENERAL","OTHER").contains(category)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工单分类无效");
    Long departmentId=jdbc.queryForObject("SELECT department_id FROM users WHERE id=?",Long.class,user.id());
    String queueCode=switch(category.toUpperCase()){case "NETWORK"->"NETWORK";case "ACCESS"->"ACCESS";case "SOFTWARE"->"SOFTWARE";default->"GENERAL";};
    Long queueId=jdbc.query("SELECT id FROM support_queues WHERE code=? AND active=true",(rs,n)->rs.getLong(1),queueCode).stream().findFirst()
        .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"该分类的处理队列已停用，请联系管理员或选择其他分类"));
    int slaHours=SLA_HOURS.getOrDefault(priority.toUpperCase(),24);
    Long id=jdbc.queryForObject("INSERT INTO tickets(requester_id,department_id,queue_id,title,description,category,priority,status,due_at) VALUES (?,?,?,?,?,?,?,'NEW',now()+make_interval(hours=>?)) RETURNING id", Long.class, user.id(), departmentId, queueId, req.title().trim(), req.description().trim(), category, priority, slaHours);
    audit.log(user.id(), "TICKET_CREATED", "TICKET", id, Map.of("title", req.title())); return get(id,user);
  }

  @Transactional
  public Ticket transition(long id, TransitionRequest req, UserContext user) {
    Ticket current=get(id,user,true); String next=req.status().trim().toUpperCase(Locale.ROOT);
    // 请求人只能对自己的已解决工单做确认关闭/重开；其余流转需要处理人权限
    boolean requesterAction = current.requesterId()==user.id() && "RESOLVED".equals(current.status()) && REQUESTER_TRANSITIONS.contains(next);
    if (!requesterAction) auth.requireAgent(user);
    if (!TRANSITIONS.getOrDefault(current.status(), Set.of()).contains(next)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不允许从 "+current.status()+" 流转到 "+next);
    if (("CLOSED".equals(next)||"RESOLVED".equals(next)) && (req.reason()==null||req.reason().isBlank())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"解决或关闭必须填写原因");
    if (requesterAction && !user.agent() && req.assigneeId()!=null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请求人不能修改处理人");
    Long assigneeId=req.assigneeId();
    if (assigneeId == null && "IN_PROGRESS".equals(next) && current.assigneeId() == null && user.queueIds().contains(current.queueId()) && user.agent()) assigneeId=user.id();
    if (assigneeId != null) validateAssignee(assigneeId, current.queueId());
    if (assigneeId == null && ("ASSIGNED".equals(next) || "IN_PROGRESS".equals(next)) && current.assigneeId() == null) {
      assigneeId=jdbc.query("SELECT qm.user_id FROM queue_members qm JOIN users u ON u.id=qm.user_id JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE qm.queue_id=? AND u.active=true AND u.account_status='ACTIVE' AND r.code='AGENT' ORDER BY qm.user_id LIMIT 1", (rs,n)->rs.getLong(1), current.queueId()).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"当前队列没有可用处理人"));
    }
    jdbc.update("UPDATE tickets SET status=?, assignee_id=COALESCE(?,assignee_id), closed_reason=CASE WHEN ?='CLOSED' THEN ? ELSE closed_reason END, "
        + "resolved_at=CASE WHEN ?='RESOLVED' AND resolved_at IS NULL THEN now() WHEN ?='IN_PROGRESS' THEN NULL ELSE resolved_at END, updated_at=now() WHERE id=?",
        next, assigneeId, next, req.reason(), next, next, id);
    if (req.reason()!=null && !req.reason().isBlank()) jdbc.update("INSERT INTO ticket_comments(ticket_id,author_id,content) VALUES (?,?,?)", id,user.id(),"["+statusName(next)+"] "+req.reason().trim());
    audit.log(user.id(), "TICKET_STATUS_CHANGED", "TICKET", id, Map.of("from",current.status(),"to",next,"reason",Objects.toString(req.reason(),"")));
    notifyTransition(current, next, assigneeId, user, requesterAction);
    return get(id,user);
  }

  /** 站内通知：请求人知悉状态变更、被指派处理人收到指派提醒；操作者本人不通知自己。 */
  private void notifyTransition(Ticket current, String next, Long assigneeId, UserContext user, boolean requesterAction) {
    Long finalAssignee = assigneeId != null ? assigneeId : current.assigneeId();
    if (current.requesterId() != user.id())
      notifications.notify(current.requesterId(), "TICKET_STATUS_CHANGED", "您的工单「" + current.title() + "」已更新为 " + statusName(next), "/workspace/tickets?ticket=" + current.id(), "TICKET", current.id(), null);
    if (requesterAction && finalAssignee != null && finalAssignee != user.id())
      notifications.notify(finalAssignee, "TICKET_STATUS_CHANGED", "请求人已将工单「" + current.title() + "」置为 " + statusName(next), "/workspace/agent?ticket=" + current.id(), "TICKET", current.id(), null);
    if (finalAssignee != null && !Objects.equals(finalAssignee,current.assigneeId()) && finalAssignee != user.id())
      notifications.notify(finalAssignee, "TICKET_ASSIGNED", "工单「" + current.title() + "」已指派给您", "/workspace/agent", "TICKET", current.id(), null);
  }

  private String statusName(String status) {
    return switch(status) { case "NEW"->"新建"; case "TRIAGED"->"已分析"; case "ASSIGNED"->"已分派"; case "IN_PROGRESS"->"处理中"; case "PENDING_USER"->"等待用户"; case "RESOLVED"->"已解决"; case "CLOSED"->"已关闭"; default->status; };
  }

  private void validateAssignee(long assigneeId, Long queueId) {
    if (queueId == null || jdbc.queryForObject("SELECT count(*) FROM queue_members WHERE queue_id=? AND user_id=?", Integer.class, queueId, assigneeId) == 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处理人必须属于当前工单队列");
    if (jdbc.queryForObject("SELECT count(*) FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.id=? AND u.active=true AND u.account_status='ACTIVE' AND r.code='AGENT'", Integer.class, assigneeId) == 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处理人账号不可用");
  }

  private static final String COMMENT_SELECT = "SELECT c.id,c.ticket_id,c.author_id,coalesce(u.display_name,'用户 #'||c.author_id) AS author_name,c.content,c.created_at FROM ticket_comments c LEFT JOIN users u ON u.id=c.author_id";

  @Transactional
  public Comment comment(long id, CommentRequest req, UserContext user) {
    Ticket ticket=get(id,user); Long commentId=jdbc.queryForObject("INSERT INTO ticket_comments(ticket_id,author_id,content) VALUES (?,?,?) RETURNING id", Long.class,id,user.id(),req.content());
    jdbc.update("UPDATE tickets SET updated_at=now() WHERE id=?",id);
    audit.log(user.id(), "TICKET_COMMENTED", "TICKET", id, Map.of());
    if (ticket.requesterId()!=user.id()) notifications.notify(ticket.requesterId(),"TICKET_COMMENTED","工单「"+ticket.title()+"」收到新回复","/workspace/tickets?ticket="+id,"TICKET",id,null);
    if (ticket.assigneeId()!=null && ticket.assigneeId()!=user.id() && ticket.assigneeId()!=ticket.requesterId()) notifications.notify(ticket.assigneeId(),"TICKET_COMMENTED","工单「"+ticket.title()+"」收到新回复","/workspace/agent?ticket="+id,"TICKET",id,null);
    return jdbc.queryForObject(COMMENT_SELECT+" WHERE c.id=?", (rs,n)->new Comment(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant()), commentId);
  }

  public List<Comment> comments(long id, UserContext user) {
    get(id, user);
    return jdbc.query(COMMENT_SELECT+" WHERE c.ticket_id=? ORDER BY c.created_at", (rs,n)->new Comment(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant()), id);
  }

  public List<Attachment> attachments(long id, UserContext user) {
    get(id, user);
    return jdbc.query("SELECT id,ticket_id,file_name,content_type,size_bytes,object_key,created_at FROM ticket_attachments WHERE ticket_id=? ORDER BY created_at", (rs,n)->new Attachment(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getTimestamp(7).toInstant()), id);
  }

  /** 附件下载来源：校验工单可见性后返回对象元数据，由控制器直接流式输出。 */
  public Map<String,Object> attachmentFile(long ticketId, long attachmentId, UserContext user) {
    get(ticketId, user);
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT file_name AS \"fileName\",content_type AS \"contentType\",object_key AS \"objectKey\" FROM ticket_attachments WHERE id=? AND ticket_id=?",attachmentId,ticketId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"附件不存在");
    return rows.get(0);
  }

  public Attachment attach(long id, MultipartFile file, UserContext user) {
    get(id, user); if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件不能为空");
    String key = storage.upload(id, file); Long attachmentId = jdbc.queryForObject("INSERT INTO ticket_attachments(ticket_id,file_name,content_type,size_bytes,object_key,created_by) VALUES (?,?,?,?,?,?) RETURNING id", Long.class, id, file.getOriginalFilename(), file.getContentType(), file.getSize(), key, user.id());
    audit.log(user.id(), "TICKET_ATTACHMENT_ADDED", "TICKET", id, Map.of("attachmentId", attachmentId, "fileName", Objects.toString(file.getOriginalFilename(), "")));
    return jdbc.queryForObject("SELECT id,ticket_id,file_name,content_type,size_bytes,object_key,created_at FROM ticket_attachments WHERE id=?", (rs,n)->new Attachment(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getTimestamp(7).toInstant()), attachmentId);
  }

  private Ticket map(java.sql.ResultSet rs,int n) throws java.sql.SQLException { return new Ticket(rs.getLong("id"),rs.getLong("requester_id"),(Long)rs.getObject("assignee_id"),rs.getString("title"),rs.getString("description"),rs.getString("category"),rs.getString("priority"),rs.getString("status"),(Long)rs.getObject("department_id"),rs.getString("department_name"),(Long)rs.getObject("queue_id"),rs.getString("queue_code"),rs.getString("queue_name"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),rs.getTimestamp("due_at")==null?null:rs.getTimestamp("due_at").toInstant(),rs.getString("requester_name"),rs.getString("assignee_name")); }
}
