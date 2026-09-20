package com.agentdesk.service;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;

/**
 * 流式问答：检索（含个人/企业范围）→ SSE 事件 stage/citations（先于首个 token）→
 * 透传 agent /v1/answer/stream 的 token 事件 → done/error；全程落库（会话+消息+引用）。
 */
@Service
public class AskStreamService {
  private static final Logger log = LoggerFactory.getLogger(AskStreamService.class);
  private final QaHistoryService qa; private final KnowledgeService knowledge; private final VaultService vault;
  private final ObjectMapper mapper; private final HttpClient http; private final String agentBaseUrl; private final String agentToken;
  private final ExecutorService executor;

  public AskStreamService(QaHistoryService qa, KnowledgeService knowledge, VaultService vault, ObjectMapper mapper,
                          @Value("${agent.base-url:http://localhost:8000}") String agentBaseUrl,
                          @Value("${agent.service-token:}") String agentToken) {
    this.qa=qa; this.knowledge=knowledge; this.vault=vault; this.mapper=mapper; this.agentBaseUrl=agentBaseUrl; this.agentToken=agentToken;
    this.http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    this.executor=Executors.newFixedThreadPool(8, r->{ Thread t=new Thread(r,"ask-stream-"+c.incrementAndGet()); t.setDaemon(true); return t; });
  }
  private static final AtomicInteger c=new AtomicInteger();

  public SseEmitter stream(KnowledgeAskRequest req, UserContext user) {
    SseEmitter emitter=new SseEmitter(300_000L);
    AtomicReference<InputStream> upstream=new AtomicReference<>();
    AtomicReference<Future<?>> task=new AtomicReference<>();
    AtomicBoolean cancelled=new AtomicBoolean();
    Runnable cancel=()->{
      cancelled.set(true);
      InputStream stream=upstream.getAndSet(null);
      if (stream!=null) try { stream.close(); } catch (IOException ignored) {}
      Future<?> running=task.get();
      if (running!=null) running.cancel(true);
    };
    emitter.onCompletion(cancel);
    emitter.onTimeout(cancel);
    emitter.onError(error->cancel.run());
    Future<?> submitted=executor.submit(() -> run(emitter,req,user,upstream,cancelled));
    task.set(submitted);
    if (cancelled.get()) submitted.cancel(true);
    return emitter;
  }

  private void run(SseEmitter emitter, KnowledgeAskRequest req, UserContext user, AtomicReference<InputStream> upstream, AtomicBoolean cancelled) {
    long started=System.currentTimeMillis();
    int[] ttfb={-1};
    String scope=normalizeScope(req.scope());
    try {
      long conversationId=qa.getOrCreateConversation(user.id(),req.conversationId(),req.question());
      qa.saveUserMessage(conversationId,req.question(),scope);
      List<KnowledgeHit> hits=retrieve(req.question(),scope,user);
      send(emitter,"stage",Map.of("step","retrieving","elapsedMs",elapsed(started)));
      send(emitter,"citations",Map.of("hits",hits));
      if (hits.isEmpty()) {
        String text="当前知识库没有足够证据，无法可靠回答。可以点击“转人工”带上问题上下文创建工单。";
        long messageId=qa.saveAnswer(conversationId,text,false,"NO_EVIDENCE",0,elapsed(started),elapsed(started),scope,List.of());
        send(emitter,"error",Map.of("reason","NO_EVIDENCE","messageId",messageId,"conversationId",conversationId));
        return;
      }
      Map<String,Object> body=new LinkedHashMap<>();
      body.put("question",req.question());
      if (req.provider()!=null&&!req.provider().isBlank()) body.put("provider",req.provider().trim());
      body.put("departmentId",user.departmentId());
      body.put("departmentName",Objects.toString(user.departmentName(),""));
      body.put("allowedQueues",user.queueIds());
      body.put("knowledgeContext",hits.stream().map(this::toEvidence).toList());
      HttpRequest.Builder hb=HttpRequest.newBuilder(URI.create(agentBaseUrl+"/v1/answer/stream"))
          .timeout(Duration.ofSeconds(240))
          .header("Content-Type","application/json")
          .header("Accept","text/event-stream")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),StandardCharsets.UTF_8));
      if (!agentToken.isBlank()) hb.header("X-Agent-Token",agentToken);
      HttpResponse<InputStream> response=http.send(hb.build(),HttpResponse.BodyHandlers.ofInputStream());
      upstream.set(response.body());
      if (cancelled.get()) { response.body().close(); return; }
      if (response.statusCode()>=300) throw new IllegalStateException("Agent 流式接口返回 "+response.statusCode());

      StringBuilder answer=new StringBuilder();
      boolean terminal=false; boolean streamOk=true; String failureReason=null; String source="LLM"; double confidence=0.8;
      try (BufferedReader reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
        String line; String event=null; StringBuilder data=new StringBuilder();
        while ((line=reader.readLine())!=null) {
          if (line.isEmpty()) {
            String payloadText=data.toString().strip();
            if (event!=null&&!payloadText.isEmpty()) {
              try {
                Map<String,Object> payload=mapper.readValue(payloadText,new TypeReference<>(){});
                switch (event) {
                  case "token" -> {
                    if (ttfb[0]<0) ttfb[0]=elapsed(started);
                    String delta=Objects.toString(payload.get("delta"),"");
                    answer.append(delta);
                    send(emitter,"token",payload);
                  }
                  case "done" -> {
                    terminal=true;
                    source=Objects.toString(payload.get("source"),"LLM");
                    confidence=((Number)payload.getOrDefault("confidence",0.8)).doubleValue();
                    if (payload.containsKey("failureReason")) failureReason=Objects.toString(payload.get("failureReason"));
                  }
                  case "error" -> { terminal=true; streamOk=false; failureReason=Objects.toString(payload.get("reason"),"AGENT_ERROR"); }
                  default -> { /* agent 侧 stage/citations 已在本地处理，忽略 */ }
                }
              } catch (com.fasterxml.jackson.core.JsonProcessingException e) { log.warn("SSE 帧解析失败",e); }
            }
            event=null; data.setLength(0);
          } else if (line.startsWith("event:")) event=line.substring("event:".length()).trim();
          else if (line.startsWith("data:")) data.append(line.substring("data:".length()).trim());
          else data.append(line.trim());
        }
      }
      if (!terminal) { streamOk=false; failureReason="STREAM_INTERRUPTED"; }
      String text=answer.toString().strip();
      if (text.isEmpty() && streamOk) { streamOk=false; failureReason="EMPTY_ANSWER"; }
      if (!streamOk && text.isEmpty()) text="回答生成失败："+(failureReason==null?"未知原因":failureReason);
      long messageId=qa.saveAnswer(conversationId,text,streamOk,streamOk?source:Objects.toString(failureReason,"AGENT_ERROR"),
          streamOk?confidence:0,ttfb[0]<0?null:ttfb[0],elapsed(started),scope,hits);
      if (streamOk) send(emitter,"done",Map.of("messageId",messageId,"conversationId",conversationId,"totalMs",elapsed(started),"source",source,"confidence",confidence,"citations",qa.citations(messageId)));
      else send(emitter,"error",Map.of("reason",failureReason==null?"AGENT_ERROR":failureReason,"messageId",messageId,"conversationId",conversationId));
    } catch (Exception e) {
      if (!cancelled.get()) {
        log.warn("流式问答失败",e);
        try { send(emitter,"error",Map.of("reason","AGENT_UNAVAILABLE")); } catch (Exception ignored) {}
      }
    } finally {
      InputStream stream=upstream.getAndSet(null);
      if (stream!=null) try { stream.close(); } catch (IOException ignored) {}
      try { emitter.complete(); } catch (Exception ignored) {}
    }
  }

  /** 个人/企业/合并检索；个人与企业物理隔离在不同表，合并时个人优先填充前 3 条。 */
  private List<KnowledgeHit> retrieve(String question, String scope, UserContext user) {
    if ("PERSONAL".equals(scope)) return vault.searchNotes(question,user);
    List<KnowledgeHit> enterprise=knowledge.search(question,user);
    if (!"ALL".equals(scope)) return enterprise;
    List<KnowledgeHit> personal=vault.searchNotes(question,user);
    List<KnowledgeHit> merged=new ArrayList<>(personal.size()>3?personal.subList(0,3):personal);
    for (KnowledgeHit hit : enterprise) if (merged.size()<5) merged.add(hit);
    return merged;
  }

  private Map<String,Object> toEvidence(KnowledgeHit h) {
    Map<String,Object> m=new LinkedHashMap<>();
    m.put("citation",h.citation());
    m.put("articleId",h.articleId());
    m.put("versionId",h.versionId());
    m.put("title",h.title());
    m.put("snippet",h.snippet());
    if (h.headingPath()!=null) m.put("headingPath",h.headingPath());
    if (h.page()!=null) m.put("page",h.page());
    return m;
  }

  private void send(SseEmitter emitter, String name, Object data) {
    try { emitter.send(SseEmitter.event().name(name).data(data)); }
    catch (IOException e) { throw new RuntimeException("SSE 发送失败（客户端可能已断开）",e); }
  }

  private String normalizeScope(String scope) {
    if (scope==null||scope.isBlank()) return "ENTERPRISE";
    String s=scope.trim().toUpperCase(Locale.ROOT);
    return Set.of("PERSONAL","ALL","ENTERPRISE").contains(s)?s:"ENTERPRISE";
  }

  private int elapsed(long started) { return (int)(System.currentTimeMillis()-started); }

  private String brief(Exception e) {
    String msg=e.getMessage();
    return msg==null||msg.isBlank()?e.getClass().getSimpleName():msg;
  }
}
