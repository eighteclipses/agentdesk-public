package com.agentdesk.controller;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import com.agentdesk.service.AgentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
  private final AgentService service; private final AuthContext auth;
  public AgentController(AgentService service,AuthContext auth){this.service=service;this.auth=auth;}
  @PostMapping("/classify") public Map<String,Object> classify(@Valid @RequestBody ClassifyRequest body,HttpServletRequest req){return service.classify(body,auth.current(req));}
  @PostMapping("/retrieve-draft") public Map<String,Object> draft(@Valid @RequestBody DraftRequest body,HttpServletRequest req){return service.draft(body,auth.current(req));}
  @PostMapping("/recommend") public Map<String,Object> recommend(@Valid @RequestBody RecommendRequest body,HttpServletRequest req){return service.recommend(body,auth.current(req));}
  @GetMapping("/providers") public Map<String,Object> providers(){return service.providers();}
  @GetMapping("/actions") public List<AgentAction> pending(HttpServletRequest req){return service.pending(auth.current(req));}
  @PostMapping("/actions") public AgentAction action(@Valid @RequestBody AgentActionRequest body,HttpServletRequest req){return service.createAction(body,auth.current(req));}
  @PostMapping("/actions/{id}/approve") public AgentAction approve(@PathVariable long id,HttpServletRequest req){return service.decide(id,true,auth.current(req));}
  @PostMapping("/actions/{id}/reject") public AgentAction reject(@PathVariable long id,HttpServletRequest req){return service.decide(id,false,auth.current(req));}
}
