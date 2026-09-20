package com.agentdesk.controller;

import com.agentdesk.api.ApiModels.*;
import com.agentdesk.security.AuthContext;
import com.agentdesk.service.ImportService;
import com.agentdesk.service.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Import Center: batch upload (multiple files), progress SSE, single-item retry/cancel, batch review and publish. */
@RestController
@RequestMapping("/api/admin/imports")
public class ImportController {
  private final ImportService service; private final AuthContext auth; private final StorageService storage;
  public ImportController(ImportService service, AuthContext auth, StorageService storage) { this.service=service; this.auth=auth; this.storage=storage; }

  @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public BatchCreateResult create(@RequestParam(defaultValue="") String name,
                                  @RequestParam(required=false) Long departmentId,
                                  @RequestParam(required=false) String provider,
                                  @RequestParam(defaultValue="skip") String duplicateStrategy,
                                  @RequestParam(required=false) String[] sourcePaths,
                                  @RequestPart("files") MultipartFile[] files,
                                  HttpServletRequest req) {
    return service.createBatch(name,files,sourcePaths,departmentId,provider,duplicateStrategy,auth.current(req));
  }

  @GetMapping public List<ImportBatchSummary> list(HttpServletRequest req) { return service.batches(auth.current(req)); }

  @GetMapping("/{id}") public Map<String,Object> batch(@PathVariable long id, HttpServletRequest req) { return service.batch(id,auth.current(req)); }

  @GetMapping(value="/{id}/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@PathVariable long id, HttpServletRequest req) { return service.subscribe(id,auth.current(req)); }

  @PostMapping("/items/{id}/retry") public ImportItem retry(@PathVariable long id, HttpServletRequest req) { return service.retry(id,auth.current(req)); }

  @PostMapping("/items/{id}/cancel") public ImportItem cancel(@PathVariable long id, HttpServletRequest req) { return service.cancel(id,auth.current(req)); }

  @PostMapping("/batches/{id}/publish") public Map<String,Object> publish(@PathVariable long id, HttpServletRequest req) {
    return Map.of("published",service.publishBatch(id,auth.current(req)));
  }

  /** Download the original file of an import item (usable in any status, for troubleshooting failed imports). */
  @GetMapping("/items/{id}/file")
  public ResponseEntity<InputStreamResource> itemFile(@PathVariable long id, HttpServletRequest req) {
    Map<String,Object> file=service.itemFile(id,auth.current(req));
    String fileName=Objects.toString(file.get("fileName"),"download");
    String encoded=URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+","%20");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"download\"; filename*=UTF-8''"+encoded)
        .contentType(MediaType.parseMediaType(Objects.toString(file.getOrDefault("contentType","application/octet-stream"),"application/octet-stream")))
        .body(new InputStreamResource(storage.download(Objects.toString(file.get("objectKey")))));
  }
}