package com.agentdesk.controller;

import com.agentdesk.api.ApiModels.UserContext;
import com.agentdesk.security.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
  private final JdbcTemplate jdbc; private final AuthContext auth; private final ObjectMapper mapper;
  public AuditController(JdbcTemplate jdbc,AuthContext auth,ObjectMapper mapper){this.jdbc=jdbc;this.auth=auth;this.mapper=mapper;}

  /** 分页审计日志；不传 page/size 时保持旧行为（前 200 条）。 */
  @GetMapping public Object list(@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size,@RequestParam(required=false) String action,HttpServletRequest req){
    UserContext u=auth.current(req); if(!u.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要管理员权限");
    String where=""; List<Object> args=new ArrayList<>();
    if(action!=null&&!action.isBlank()){ where=" WHERE action ILIKE ?"; args.add("%"+action.strip()+"%"); }
    List<Map<String,Object>> items;
    if(page==null&&size==null&&where.isEmpty()) items=jdbc.queryForList("SELECT id,actor_id,action,resource_type,resource_id,detail::text AS detail,created_at FROM audit_logs ORDER BY created_at DESC LIMIT 200");
    else {
      int p=Math.max(1,page==null?1:page); int s=Math.min(Math.max(1,size==null?50:size),200);
      Long total=jdbc.queryForObject("SELECT count(*) FROM audit_logs"+where,Long.class,args.toArray());
      List<Object> pageArgs=new ArrayList<>(args); pageArgs.add(s); pageArgs.add((p-1)*s);
      items=jdbc.queryForList("SELECT id,actor_id,action,resource_type,resource_id,detail::text AS detail,created_at FROM audit_logs"+where+" ORDER BY created_at DESC LIMIT ? OFFSET ?",pageArgs.toArray());
      return Map.of("items",decodeDetail(items),"total",total==null?0:total);
    }
    return decodeDetail(items);
  }

  /** 审计日志导出 CSV（支持按动作名过滤，同列表页过滤条件），最多 10000 行防止内存失控。 */
  @GetMapping(value="/export",produces="text/csv")
  public ResponseEntity<String> export(@RequestParam(required=false) String action,HttpServletRequest req){
    UserContext u=auth.current(req); if(!u.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要管理员权限");
    StringBuilder where=new StringBuilder(); List<Object> args=new ArrayList<>();
    if(action!=null&&!action.isBlank()){ where.append(" WHERE a.action ILIKE ?"); args.add("%"+action.strip()+"%"); }
    List<Map<String,Object>> rows=jdbc.queryForList("SELECT a.id,a.actor_id,coalesce(usr.username,'user #'||a.actor_id) AS actor,a.action,a.resource_type,a.resource_id,a.detail::text AS detail,a.created_at FROM audit_logs a LEFT JOIN users usr ON usr.id=a.actor_id"+where+" ORDER BY a.created_at DESC LIMIT 10000",args.toArray());
    StringBuilder csv=new StringBuilder("\uFEFFid,时间,操作人,动作,资源,详情");
    for(Map<String,Object> r : rows){
      csv.append('\n').append(r.get("id")).append(',')
          .append(cell(String.valueOf(r.get("created_at")))).append(',')
          .append(cell(String.valueOf(r.get("actor")))).append(',')
          .append(cell(String.valueOf(r.get("action")))).append(',')
          .append(cell(r.get("resource_type")+" #"+r.get("resource_id"))).append(',')
          .append(cell(Objects.toString(r.get("detail"),"")));
    }
    String fileName="audit-"+java.time.LocalDate.now()+".csv";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+fileName+"\"")
        .contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
        .body(csv.toString());
  }

  private static String cell(String v){
    if(v==null) return "";
    boolean needQuote=v.indexOf(',')>=0||v.indexOf('"')>=0||v.indexOf('\n')>=0;
    String escaped=v.replace("\"","\"\"");
    return needQuote?"\""+escaped+"\"":escaped;
  }

  /** detail 列是 jsonb：转成结构化对象返回，避免前端看到 PGobject 包装（{"type":"jsonb",...}）。 */
  private List<Map<String,Object>> decodeDetail(List<Map<String,Object>> items){
    for(Map<String,Object> row : items){
      Object d=row.get("detail");
      if(d instanceof String text&&!text.isBlank()){
        try{ row.put("detail",mapper.readValue(text,Object.class)); }
        catch(Exception ignored){ /* 非 JSON 内容原样返回 */ }
      }
    }
    return items;
  }
}
