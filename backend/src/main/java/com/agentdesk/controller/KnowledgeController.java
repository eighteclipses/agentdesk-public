package com.agentdesk.controller;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import com.agentdesk.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
  private final KnowledgeService service; private final AuthContext auth; private final AgentService agent;
  private final AskStreamService askStream; private final QaHistoryService qa; private final StorageService storage;
  private final com.agentdesk.security.RateLimiter askRateLimiter;
  public KnowledgeController(KnowledgeService service,AuthContext auth,AgentService agent,AskStreamService askStream,QaHistoryService qa,StorageService storage,
                             @org.springframework.beans.factory.annotation.Qualifier("askRateLimiter") com.agentdesk.security.RateLimiter askRateLimiter){
    this.service=service;this.auth=auth;this.agent=agent;this.askStream=askStream;this.qa=qa;this.storage=storage;this.askRateLimiter=askRateLimiter;}

  @GetMapping public Object list(@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size,@RequestParam(required=false) String q,@RequestParam(required=false) String category,@RequestParam(required=false) String tag,@RequestParam(required=false) String status,HttpServletRequest req){
    // 传 page/size 时返回分页结构（items+total），否则保持旧的全量数组兼容老调用方
    if (page!=null||size!=null) return service.listPage(auth.current(req),page==null?1:page,size==null?20:size,q,category,tag,status);
    return service.list(auth.current(req));
  }
  @GetMapping("/search") public Object search(@RequestParam String q,@RequestParam(required=false) String category,@RequestParam(required=false) String tag,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size,HttpServletRequest req){
    if (page!=null||size!=null) return service.searchPage(q,auth.current(req),category,tag,page,size);
    return service.search(q,auth.current(req));
  }
  @GetMapping("/categories") public List<String> categories(HttpServletRequest req){return service.categories(auth.current(req));}
  @GetMapping("/tags") public List<Map<String,Object>> tags(HttpServletRequest req){return service.tags(auth.current(req));}
  @PostMapping public KnowledgeArticle create(@Valid @RequestBody KnowledgeCreateRequest body,HttpServletRequest req){return service.create(body,auth.current(req));}
  @PutMapping("/articles/{id}") public Map<String,Object> update(@PathVariable long id,@RequestBody KnowledgeUpdateRequest body,HttpServletRequest req){return service.update(id,body,auth.current(req));}
  @PostMapping("/{id}/publish") public KnowledgeArticle publish(@PathVariable long id,HttpServletRequest req){return service.publish(id,auth.current(req));}
  @PostMapping("/ask") public KnowledgeAskResponse ask(@Valid @RequestBody KnowledgeAskRequest body,HttpServletRequest req){UserContext user=auth.current(req);throttleAsk(user);return agent.ask(body,user);}

  // ---- 流式问答 ----
  @PostMapping(value="/ask/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter askStream(@Valid @RequestBody KnowledgeAskRequest body,HttpServletRequest req){UserContext user=auth.current(req);throttleAsk(user);return askStream.stream(body,user);}

  /** 每用户限流，防止刷 LLM 调用成本 */
  private void throttleAsk(UserContext user){
    if(!askRateLimiter.tryAcquire("user:"+user.id()))
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"提问过于频繁，请稍后再试");
  }

  @PostMapping("/feedback") public Map<String,String> feedback(@Valid @RequestBody FeedbackRequest body,HttpServletRequest req){
    qa.saveFeedback(auth.current(req).id(),body); return Map.of("ok","true");
  }
  @GetMapping("/conversations") public List<ConversationSummary> conversations(HttpServletRequest req){return qa.conversations(auth.current(req).id());}
  @GetMapping("/conversations/{id}") public ConversationDetail conversation(@PathVariable long id,HttpServletRequest req){return qa.conversation(auth.current(req).id(),id);}
  @DeleteMapping("/conversations/{id}") public Map<String,String> deleteConversation(@PathVariable long id,HttpServletRequest req){qa.deleteConversation(auth.current(req).id(),id); return Map.of("ok","true");}
  @PostMapping("/citations/{id}/click") public Map<String,String> citationClick(@PathVariable long id,HttpServletRequest req){
    qa.markCitationClicked(id,auth.current(req).id()); return Map.of("ok","true");
  }

  // ---- 文章详情 / 版本 / 分块 / 原文件 ----
  @GetMapping("/articles/{id}") public Map<String,Object> detail(@PathVariable long id,@RequestParam(required=false) Long versionId,HttpServletRequest req){return service.detail(id,auth.current(req),versionId);}
  @GetMapping("/articles/{id}/versions") public List<ArticleVersionInfo> versions(@PathVariable long id,HttpServletRequest req){return service.versions(id,auth.current(req));}
  @GetMapping("/articles/{id}/chunks") public List<ChunkInfo> chunks(@PathVariable long id,HttpServletRequest req){return service.chunks(id,auth.current(req));}
  @PostMapping("/articles/{id}/review") public KnowledgeArticle review(@PathVariable long id,@Valid @RequestBody ReviewRequest body,HttpServletRequest req){return service.review(id,body.approve(),body.comment(),auth.current(req));}
  @PostMapping("/articles/{id}/retract") public KnowledgeArticle retract(@PathVariable long id,HttpServletRequest req){return service.retract(id,auth.current(req));}

  @GetMapping("/articles/{id}/file")
  public ResponseEntity<InputStreamResource> file(@PathVariable long id,HttpServletRequest req){
    Map<String,Object> file=service.fileSource(id,auth.current(req));
    InputStream stream=storage.download(Objects.toString(file.get("objectKey")));
    String fileName=Objects.toString(file.get("fileName"),"download");
    String encoded=URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+","%20");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"download\"; filename*=UTF-8''"+encoded)
        .contentType(MediaType.parseMediaType(Objects.toString(file.get("contentType"),"application/octet-stream")))
        .body(new InputStreamResource(stream));
  }
}