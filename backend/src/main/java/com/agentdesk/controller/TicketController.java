package com.agentdesk.controller;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import com.agentdesk.service.StorageService;
import com.agentdesk.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
  private final TicketService service; private final AuthContext auth; private final StorageService storage;
  public TicketController(TicketService service, AuthContext auth, StorageService storage){this.service=service;this.auth=auth;this.storage=storage;}
  @GetMapping public Page<Ticket> list(@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size,@RequestParam(required=false) String status,@RequestParam(required=false) String priority,@RequestParam(required=false) String q,@RequestParam(required=false) String view,@RequestParam(required=false) String sort,HttpServletRequest req){return service.list(auth.current(req),page,size,status,priority,q,view,sort);}
  @GetMapping("/{id}") public Ticket get(@PathVariable long id,HttpServletRequest req){return service.get(id,auth.current(req));}
  @GetMapping("/{id}/assignees") public java.util.List<AssigneeOption> assignees(@PathVariable long id,HttpServletRequest req){return service.assignees(id,auth.current(req));}
  @PostMapping public Ticket create(@Valid @RequestBody TicketCreateRequest body,HttpServletRequest req){return service.create(body,auth.current(req));}
  @PostMapping("/{id}/transition") public Ticket transition(@PathVariable long id,@Valid @RequestBody TransitionRequest body,HttpServletRequest req){return service.transition(id,body,auth.current(req));}
  @PostMapping("/{id}/comments") public Comment comment(@PathVariable long id,@Valid @RequestBody CommentRequest body,HttpServletRequest req){return service.comment(id,body,auth.current(req));}
  @GetMapping("/{id}/comments") public java.util.List<Comment> comments(@PathVariable long id,HttpServletRequest req){return service.comments(id,auth.current(req));}
  @PostMapping(value="/{id}/attachments", consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Attachment attach(@PathVariable long id,@RequestPart("file") MultipartFile file,HttpServletRequest req){return service.attach(id,file,auth.current(req));}
  @GetMapping("/{id}/attachments") public java.util.List<Attachment> attachments(@PathVariable long id,HttpServletRequest req){return service.attachments(id,auth.current(req));}
  @GetMapping("/{id}/attachments/{attachmentId}/download")
  public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> download(@PathVariable long id,@PathVariable long attachmentId,HttpServletRequest req){
    java.util.Map<String,Object> file=service.attachmentFile(id,attachmentId,auth.current(req));
    String fileName=String.valueOf(file.get("fileName"));
    String encoded=java.net.URLEncoder.encode(fileName,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
    return org.springframework.http.ResponseEntity.ok()
        .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"download\"; filename*=UTF-8''"+encoded)
        .contentType(MediaType.parseMediaType(String.valueOf(file.getOrDefault("contentType","application/octet-stream"))))
        .body(new org.springframework.core.io.InputStreamResource(storage.download(String.valueOf(file.get("objectKey")))));
  }
}
