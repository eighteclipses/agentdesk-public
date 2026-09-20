package com.agentdesk.service;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {
  private final JdbcTemplate jdbc; private final ObjectMapper mapper; private final AuditService audit; private final KnowledgeService knowledge; private final VaultService vault; private final TicketService tickets; private final NotificationService notifications; private final HttpClient http; private final String agentBaseUrl; private final String agentToken;
  public AgentService(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit, KnowledgeService knowledge, VaultService vault, TicketService tickets, NotificationService notifications, @Value("${agent.base-url:http://localhost:8000}") String agentBaseUrl, @Value("${agent.service-token:}") String agentToken){this.jdbc=jdbc;this.mapper=mapper;this.audit=audit;this.knowledge=knowledge;this.vault=vault;this.tickets=tickets;this.notifications=notifications;this.agentBaseUrl=agentBaseUrl;this.agentToken=agentToken;this.http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();}
  public Map<String,Object> classify(ClassifyRequest req, UserContext user){ if(req.ticketId()!=null) tickets.get(req.ticketId(),user); return run("classify", req.ticketId(), user.id(), withProvider(context(base("title",req.title(),"description",req.description(),"ticketId",req.ticketId()),user,knowledge.search(req.title()+" "+req.description(),user)), req.provider()), "/v1/classify"); }
  public Map<String,Object> draft(DraftRequest req, UserContext user){ if(req.ticketId()!=null) tickets.get(req.ticketId(),user); return run("retrieve-draft", req.ticketId(), user.id(), withProvider(context(base("question",req.question(),"ticketId",req.ticketId()),user,knowledge.search(req.question(),user)), req.provider()), "/v1/retrieve-draft"); }
  public Map<String,Object> recommend(RecommendRequest req, UserContext user){ if(req.ticketId()!=null) tickets.get(req.ticketId(),user); return run("recommend", req.ticketId(), user.id(), withProvider(context(base("title",req.title(),"description",req.description(),"ticketId",req.ticketId()),user,knowledge.search(req.title()+" "+req.description(),user)), req.provider()), "/v1/recommend"); }

  /** 组装转发给 agent 的字段；值为 null 的键跳过（如未关联工单时的 ticketId） */
  private static Map<String,Object> base(Object... kv){
    Map<String,Object> m=new LinkedHashMap<>();
    for(int i=0;i<kv.length;i+=2) if(kv[i+1]!=null) m.put((String)kv[i],kv[i+1]);
    return m;
  }
  public KnowledgeAskResponse ask(KnowledgeAskRequest req, UserContext user){
    List<KnowledgeHit> hits=retrieve(req,user);
    Map<String,Object> result=run("answer",null,user.id(),withProvider(context(Map.of("question",req.question()),user,hits), req.provider()),"/v1/answer");
    return new KnowledgeAskResponse(Boolean.TRUE.equals(result.get("ok")),Objects.toString(result.get("answer"),""),((Number)result.getOrDefault("confidence",0.0)).doubleValue(),hits,Objects.toString(result.get("failureReason"),null),Objects.toString(result.getOrDefault("source","RULE_FALLBACK"),"RULE_FALLBACK"));
  }

  /** 个人/企业/合并检索，与流式问答保持同一语义；合并时个人优先填充前 3 条。 */
  private List<KnowledgeHit> retrieve(KnowledgeAskRequest req, UserContext user) {
    String scope=req.scope()==null||req.scope().isBlank()?"ENTERPRISE":req.scope().toUpperCase(java.util.Locale.ROOT);
    if ("PERSONAL".equals(scope)) return vault.searchNotes(req.question(),user);
    List<KnowledgeHit> enterprise=knowledge.search(req.question(),user);
    if (!"ALL".equals(scope)) return enterprise;
    List<KnowledgeHit> personal=vault.searchNotes(req.question(),user);
    List<KnowledgeHit> merged=new ArrayList<>(personal.size()>3?personal.subList(0,3):personal);
    for (KnowledgeHit hit : enterprise) if (merged.size()<5) merged.add(hit);
    return merged;
  }
  private Map<String,Object> context(Map<String,Object> base,UserContext user,List<KnowledgeHit> hits){Map<String,Object> body=new LinkedHashMap<>(base);body.put("departmentId",user.departmentId());body.put("departmentName",user.departmentName());body.put("allowedQueues",user.queueIds());body.put("knowledgeContext",hits.stream().limit(5).toList());return body;}
  private Map<String,Object> withProvider(Map<String,Object> body,String provider){if(provider!=null&&!provider.isBlank())body.put("provider",provider.trim());return body;}
  /** 查询 Agent 服务已配置的 LLM 供应商（名称与模型，不含密钥）；不写入 agent_runs/审计。 */
  public Map<String,Object> providers(){
    try {
      HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(agentBaseUrl+"/v1/providers")).timeout(Duration.ofSeconds(5)).header("Accept","application/json"); if(!agentToken.isBlank())request.header("X-Agent-Token",agentToken);
      HttpResponse<String> response=http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8)); if(response.statusCode()>=300) throw new IllegalStateException(String.valueOf(response.statusCode()));
      return mapper.readValue(response.body(),new TypeReference<Map<String,Object>>(){});
    } catch(Exception e){ Map<String,Object> fallback=new LinkedHashMap<>(); fallback.put("providers",List.of()); fallback.put("failureReason","Agent 服务不可用："+e.getMessage()); return fallback; }
  }
  @SuppressWarnings("unchecked") private Map<String,Object> run(String type, Long ticketId, Long actorId, Map<String,Object> body, String path){
    Instant started=Instant.now(); Map<String,Object> result; String error=null;
    try { String payload=toJson(body); HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(agentBaseUrl+path)).timeout(Duration.ofSeconds(35)).header("Content-Type","application/json").header("Accept","application/json"); if(!agentToken.isBlank())request.header("X-Agent-Token",agentToken); HttpResponse<String> response=http.send(request.POST(HttpRequest.BodyPublishers.ofString(payload,java.nio.charset.StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8)); if(response.statusCode()>=300) throw new IllegalStateException(response.statusCode()+" "+response.body()); result=mapper.readValue(response.body(),new TypeReference<>(){}); if(result==null) result=Map.of("ok",false,"failureReason","Agent 返回为空"); }
    catch(Exception e){ result=new LinkedHashMap<>(); result.put("ok",false); result.put("confidence",0.0); result.put("failureReason","Agent 服务不可用："+e.getMessage()); error=e.getClass().getSimpleName(); }
    long runId=jdbc.queryForObject("INSERT INTO agent_runs(run_type,ticket_id,input_summary,output_json,elapsed_ms,error) VALUES (?,?,?,?::jsonb,?,?) RETURNING id",Long.class,type,ticketId,body.toString(),toJson(result),Duration.between(started,Instant.now()).toMillis(),error);
    result=new LinkedHashMap<>(result); result.put("runId",runId); audit.log(actorId,"AGENT_RUN","AGENT_RUN",runId,Map.of("type",type,"ticketId",Objects.toString(ticketId,""))); return result;
  }
  private String toJson(Object value){ try{return mapper.writeValueAsString(value);}catch(Exception e){return "{}";} }

  @Transactional
  public AgentAction createAction(AgentActionRequest req, UserContext user){
    if(!user.agent()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"需要处理人或管理员权限");
    String type=Objects.toString(req.actionType(),"").toUpperCase();
    if(!Set.of("TRANSFER","CLOSE").contains(type)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"不支持的 Agent 动作类型");
    Ticket current=tickets.lock(req.ticketId(),user);
    validateActionState(type,current);
    Map<String,Object> payload=req.payload()==null?new LinkedHashMap<>():new LinkedHashMap<>(req.payload());
    if("TRANSFER".equals(type)) {
      String queueCode=Objects.toString(payload.get("queueCode"),"").toUpperCase();
      if(jdbc.queryForObject("SELECT count(*) FROM support_queues WHERE code=? AND active=true",Integer.class,queueCode)==0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"目标队列不存在或已停用");
      payload.put("queueCode",queueCode);
    }
    Long id=jdbc.queryForObject("INSERT INTO agent_actions(action_type,ticket_id,payload,status,created_by) VALUES (?,?,?::jsonb,'PENDING',?) RETURNING id",Long.class,type,req.ticketId(),toJson(payload),user.id());
    audit.log(user.id(),"AGENT_ACTION_PROPOSED","AGENT_ACTION",id,Map.of("type",type)); return getAction(id);
  }
  public AgentAction getAction(long id){ return jdbc.queryForObject("SELECT id,action_type,ticket_id,status,payload,created_at,decided_at FROM agent_actions WHERE id=?",(rs,n)->new AgentAction(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getString(4),readMap(rs.getString(5)),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7)==null?null:rs.getTimestamp(7).toInstant()),id); }
  public List<AgentAction> pending(UserContext user){ if(!user.admin()&&!user.agent()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN); if(user.admin()) return jdbc.query("SELECT id,action_type,ticket_id,status,payload,created_at,decided_at FROM agent_actions WHERE status='PENDING' ORDER BY created_at DESC",(rs,n)->new AgentAction(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getString(4),readMap(rs.getString(5)),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7)==null?null:rs.getTimestamp(7).toInstant())); if(user.queueIds().isEmpty()) return List.of(); String marks=String.join(",",Collections.nCopies(user.queueIds().size(),"?")); return jdbc.query("SELECT a.id,a.action_type,a.ticket_id,a.status,a.payload,a.created_at,a.decided_at FROM agent_actions a JOIN tickets t ON t.id=a.ticket_id WHERE a.status='PENDING' AND t.queue_id IN ("+marks+") ORDER BY a.created_at DESC",(rs,n)->new AgentAction(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getString(4),readMap(rs.getString(5)),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7)==null?null:rs.getTimestamp(7).toInstant()),user.queueIds().toArray()); }
  @Transactional
  public AgentAction decide(long id, boolean approve, UserContext user){
    if(!user.agent()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN); if(jdbc.queryForList("SELECT id FROM agent_actions WHERE id=? FOR UPDATE",id).isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"动作不存在");
    AgentAction action=getAction(id); Ticket ticket=tickets.lock(action.ticketId(),user); if(!"PENDING".equals(action.status())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"动作已处理");
    jdbc.update("UPDATE agent_actions SET status=?,decided_by=?,decided_at=now() WHERE id=?",approve?"APPROVED":"REJECTED",user.id(),id);
    if (approve) { validateActionState(action.actionType(),ticket); applyApprovedAction(action,user); }
    audit.log(user.id(),approve?"AGENT_ACTION_APPROVED":"AGENT_ACTION_REJECTED","AGENT_ACTION",id,Map.of());
    notifyDecision(action, ticket, approve, user);
    return getAction(id);
  }
  /** 审批触达：提议坐席收到结论；批准的动作改变工单状态时同步告知请求人。 */
  private void notifyDecision(AgentAction action, Ticket ticket, boolean approve, UserContext user) {
    Long proposer = jdbc.queryForObject("SELECT created_by FROM agent_actions WHERE id=?", Long.class, action.id());
    if (proposer != null && proposer != user.id())
      notifications.notify(proposer, "AGENT_ACTION_DECIDED", (approve ? "你提议的 Agent 动作已通过：" : "你提议的 Agent 动作被驳回：") + "工单「" + ticket.title() + "」", "/workspace/agent?ticket="+action.ticketId(), "AGENT_ACTION", action.id(), null);
    if (approve && !"CLOSE".equalsIgnoreCase(action.actionType()) && ticket.requesterId() != user.id()) {
      String finalStatus = "CLOSE".equalsIgnoreCase(action.actionType()) ? "CLOSED" : "ASSIGNED";
      notifications.notify(ticket.requesterId(), "TICKET_STATUS_CHANGED", "您的工单「" + ticket.title() + "」已更新为 " + finalStatus, "/workspace/tickets", "TICKET", action.ticketId(), null);
    }
  }
  private void validateActionState(String type, Ticket ticket) {
    if ("CLOSE".equals(type) && !"RESOLVED".equals(ticket.status())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"请先解决工单，再审批关闭");
    if ("TRANSFER".equals(type) && Set.of("RESOLVED","CLOSED").contains(ticket.status())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"工单已解决或关闭，不能再分流");
  }
  private void applyApprovedAction(AgentAction action, UserContext user) {
    if ("TRANSFER".equalsIgnoreCase(action.actionType())) {
      String queueCode=Objects.toString(action.payload().get("queueCode"),"GENERAL");
      if (jdbc.queryForObject("SELECT count(*) FROM queue_members qm JOIN support_queues q ON q.id=qm.queue_id JOIN users u ON u.id=qm.user_id JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE q.code=? AND q.active=true AND u.active=true AND u.account_status='ACTIVE' AND r.code='AGENT'",Integer.class,queueCode)==0)
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"目标队列当前没有可用处理人");
      jdbc.update("UPDATE tickets SET queue_id=(SELECT id FROM support_queues WHERE code=? AND active=true),assignee_id=(SELECT MIN(qm.user_id) FROM queue_members qm JOIN support_queues q ON q.id=qm.queue_id JOIN users u ON u.id=qm.user_id JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE q.code=? AND q.active=true AND u.active=true AND u.account_status='ACTIVE' AND r.code='AGENT'),status='ASSIGNED',updated_at=now() WHERE id=?",queueCode,queueCode,action.ticketId());
    } else if ("CLOSE".equalsIgnoreCase(action.actionType())) {
      String reason = Objects.toString(action.payload().get("reason"), "Agent 建议关闭，经人工确认");
      tickets.transition(action.ticketId(),new TransitionRequest("CLOSED",reason,null),user);
    }
  }
  private Map<String,Object> readMap(String raw){try{return mapper.readValue(raw,new TypeReference<>(){});}catch(Exception e){return Map.of("raw",raw);} }
}
