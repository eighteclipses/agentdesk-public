package com.agentdesk.controller;

import com.agentdesk.api.ApiModels.UserContext;
import com.agentdesk.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
  private final JdbcTemplate jdbc; private final AuthContext auth;
  public DashboardController(JdbcTemplate jdbc,AuthContext auth){this.jdbc=jdbc;this.auth=auth;}
  @GetMapping public Map<String,Object> overview(HttpServletRequest req){UserContext u=auth.current(req); String filter=scope(u); Map<String,Object> out=new LinkedHashMap<>(); out.put("tickets",jdbc.queryForObject("SELECT count(*) FROM tickets"+filter,Long.class)); out.put("openTickets",jdbc.queryForObject("SELECT count(*) FROM tickets"+filter+(filter.isBlank()?" WHERE ":" AND ")+"status NOT IN ('CLOSED','RESOLVED')",Long.class)); out.put("knowledgeArticles",jdbc.queryForObject("SELECT count(*) FROM knowledge_articles WHERE status='PUBLISHED'",Long.class)); out.put("pendingActions",u.agent()?jdbc.queryForObject("SELECT count(*) FROM agent_actions WHERE status='PENDING'",Long.class):0); out.put("slaBreaches",jdbc.queryForObject("SELECT count(*) FROM tickets WHERE status NOT IN ('CLOSED','RESOLVED') AND due_at < now()"+(filter.isBlank()?"":filter.replace("WHERE","AND")),Long.class)); out.put("departmentId",u.departmentId()); out.put("departmentName",u.departmentName()); return out; }

  /** 管理员数据统计：状态/优先级分布、近 N 天新建趋势、SLA 达成率（首次解决时间 vs 截止时间）、问答与引用反馈。 */
  @GetMapping("/stats") public Map<String,Object> stats(@RequestParam(defaultValue="7") int days,HttpServletRequest req){
    auth.requireAdmin(auth.current(req)); int n=Math.min(Math.max(days,1),30);
    Map<String,Object> out=new LinkedHashMap<>(); out.put("days",n);
    out.put("status",jdbc.query("SELECT status AS label,count(*) AS count FROM tickets GROUP BY status ORDER BY count DESC",(rs,i)->new com.agentdesk.api.ApiModels.StatBucket(rs.getString(1),rs.getLong(2))));
    out.put("priority",jdbc.query("SELECT priority AS label,count(*) AS count FROM tickets GROUP BY priority ORDER BY priority",(rs,i)->new com.agentdesk.api.ApiModels.StatBucket(rs.getString(1),rs.getLong(2))));
    out.put("dailyCreated",jdbc.query("SELECT to_char(d.day,'MM-DD') AS date,count(t.id) AS count FROM generate_series(current_date-(?::int-1),current_date,interval '1 day') d(day) "
        +"LEFT JOIN tickets t ON t.created_at>=d.day AND t.created_at<d.day+interval '1 day' GROUP BY d.day ORDER BY d.day",(rs,i)->new com.agentdesk.api.ApiModels.DayPoint(rs.getString(1),rs.getLong(2)),n));
    long onTime=jdbc.queryForObject("SELECT count(*) FROM tickets WHERE resolved_at IS NOT NULL AND due_at IS NOT NULL AND resolved_at<=due_at",Long.class);
    long overdue=jdbc.queryForObject("SELECT count(*) FROM tickets WHERE resolved_at IS NOT NULL AND due_at IS NOT NULL AND resolved_at>due_at",Long.class);
    Map<String,Object> sla=new LinkedHashMap<>(); sla.put("onTime",onTime); sla.put("overdue",overdue);
    sla.put("rate",onTime+overdue==0?null:Math.round(onTime*1000.0/(onTime+overdue))/10.0);
    out.put("sla",sla);
    Map<String,Object> qa=new LinkedHashMap<>();
    qa.put("conversations",jdbc.queryForObject("SELECT count(*) FROM conversations",Long.class));
    qa.put("messages",jdbc.queryForObject("SELECT count(*) FROM messages",Long.class));
    qa.put("feedbackUp",jdbc.queryForObject("SELECT count(*) FROM knowledge_feedback WHERE rating>0",Long.class));
    qa.put("feedbackDown",jdbc.queryForObject("SELECT count(*) FROM knowledge_feedback WHERE rating<0",Long.class));
    qa.put("citationsTotal",jdbc.queryForObject("SELECT count(*) FROM citations",Long.class));
    qa.put("citationsClicked",jdbc.queryForObject("SELECT count(*) FROM citations WHERE clicked",Long.class));
    out.put("qa",qa);
    return out;
  }
  private String scope(UserContext u){ if(u.admin()) return ""; if(!u.agent()) return " WHERE requester_id="+u.id(); if(u.queueIds().isEmpty()) return " WHERE 1=0"; return " WHERE queue_id IN ("+String.join(",",u.queueIds().stream().map(String::valueOf).toList())+")"; }
}
