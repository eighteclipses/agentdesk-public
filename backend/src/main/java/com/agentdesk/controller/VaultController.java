package com.agentdesk.controller;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import com.agentdesk.service.VaultService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 个人知识库（Vault）：笔记 CRUD、双向链接、个人问答检索、图谱、"提交审核为企业知识"。 */
@RestController
@RequestMapping("/api/vault")
public class VaultController {
  private final VaultService service; private final AuthContext auth;
  public VaultController(VaultService service, AuthContext auth) { this.service=service; this.auth=auth; }

  @GetMapping("/notes") public List<Map<String,Object>> list(@RequestParam(required=false) String folder, @RequestParam(required=false) String q, HttpServletRequest req) {
    return service.list(auth.current(req),folder,q);
  }
  @PostMapping("/notes") public Note create(@Valid @RequestBody NoteUpsertRequest body, HttpServletRequest req) { return service.create(body,auth.current(req)); }
  @GetMapping("/notes/{id}") public Map<String,Object> detail(@PathVariable long id, HttpServletRequest req) { return service.detail(id,auth.current(req)); }
  @PutMapping("/notes/{id}") public Note update(@PathVariable long id, @Valid @RequestBody NoteUpsertRequest body, HttpServletRequest req) { return service.update(id,body,auth.current(req)); }
  @DeleteMapping("/notes/{id}") public Map<String,String> delete(@PathVariable long id, HttpServletRequest req) { service.delete(id,auth.current(req)); return Map.of("ok","true"); }
  @PostMapping("/notes/{id}/to-enterprise") public Map<String,Object> toEnterprise(@PathVariable long id, HttpServletRequest req) { return service.toEnterprise(id,auth.current(req)); }
  @GetMapping("/graph") public Map<String,Object> graph(@RequestParam(defaultValue="all") String scope, @RequestParam(required=false) Long ref, HttpServletRequest req) {
    return service.graph(auth.current(req),scope,ref);
  }
}