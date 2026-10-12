package cn.agentlens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import java.util.*;
import static cn.agentlens.Json.*;

@RestController
@RequestMapping("/api/v1")
public class Api {
    private final Platform p;private final ReportService reports;private final AgentClient client;private int port;
    public Api(Platform p,ReportService reports,AgentClient client){this.p=p;this.reports=reports;this.client=client;}
    @EventListener public void server(WebServerInitializedEvent event){port=event.getWebServer().getPort();}
    @GetMapping("/health")ObjectNode health(){return obj("status","UP","version","0.1.0");}
    @GetMapping("/me")ObjectNode me(Authentication a){return obj("username",a.getName(),"projects",p.projects(a));}
    @GetMapping("/projects")List<ObjectNode> projects(Authentication a){return p.projects(a);}
    @PostMapping("/projects")ObjectNode project(@RequestBody JsonNode b,Authentication a){return p.createProject(b,a);}
    @PostMapping("/projects/{id}/members")ObjectNode member(@PathVariable String id,@RequestBody JsonNode b,Authentication a){return p.member(id,b,a);}
    @GetMapping("/datasets")List<ObjectNode> datasets(@RequestParam String project_id,Authentication a){p.project(project_id,a);return p.store.list("dataset",project_id);}
    @PostMapping("/datasets/import")ObjectNode importData(@RequestBody JsonNode b,Authentication a){return p.importDataset(b,a);}
    @PostMapping("/datasets/{id}/publish")ObjectNode publish(@PathVariable String id,Authentication a){return p.publish(id,a);}
    @GetMapping("/agents")List<ObjectNode> agents(@RequestParam String project_id,Authentication a){p.project(project_id,a);return p.store.list("agent",project_id);}
    @PostMapping("/agents")ObjectNode agent(@RequestBody JsonNode b,Authentication a){return p.createAgent(b,a);}
    @PostMapping("/agents/{id}/test")ObjectNode test(@PathVariable String id,Authentication a)throws Exception{
        ObjectNode agent=p.access(id,"agent",a,"ADMIN","DEVELOPER");long start=System.nanoTime();
        try{ObjectNode response=client.call(agent,obj("case_id","connection-test","input","连接测试","reference","模拟连接测试成功"),Json.id("test"),port);return obj("ok",true,"duration_ms",(System.nanoTime()-start)/1e6,"has_trace",!response.path("trace").isEmpty(),"has_usage",response.path("usage").isObject(),"source",str(response,"source"));}
        catch(Exception e){throw new ApiError(422,"CONNECTION_FAILED","连接失败：请检查接口允许列表、密钥、超时及返回协议");}
    }
    @GetMapping("/runs")List<ObjectNode> runs(@RequestParam String project_id,Authentication a){return p.runs(project_id,a);}
    @PostMapping("/runs")ResponseEntity<ObjectNode> run(@RequestBody JsonNode b,@RequestHeader(value="Idempotency-Key",required=false)String key,Authentication a){return ResponseEntity.accepted().body(p.createRun(b,key,a));}
    @GetMapping("/runs/{id}")ObjectNode run(@PathVariable String id,Authentication a){return p.runView(id,a);}
    @PostMapping("/runs/{id}/cancel")ObjectNode cancel(@PathVariable String id,Authentication a){return p.cancel(id,a);}
    @GetMapping("/results/{id}")ObjectNode result(@PathVariable String id,Authentication a){return p.access(id,"result",a);}
    @PostMapping("/results/{id}/reviews")ObjectNode review(@PathVariable String id,@RequestBody JsonNode b,Authentication a){return p.review(id,b,a);}
    @GetMapping("/comparisons")ObjectNode comparison(@RequestParam String baseline_run_id,@RequestParam String candidate_run_id,Authentication a){return p.comparison(baseline_run_id,candidate_run_id,a);}
    @PostMapping("/traces/batch")ObjectNode traces(@RequestBody JsonNode b,Authentication a){return p.acceptTrace(b,a);}
    @PostMapping("/reports")ObjectNode report(@RequestBody JsonNode b,Authentication a){return reports.create(b,a);}
    @GetMapping("/reports")List<ObjectNode> reports(@RequestParam String project_id,Authentication a){p.project(project_id,a);return p.store.list("report",project_id).stream().map(r->{r.remove("html");return r;}).toList();}
    @GetMapping(value="/reports/{id}/html",produces="text/html;charset=UTF-8")ResponseEntity<String> reportHtml(@PathVariable String id,Authentication a){ObjectNode r=p.access(id,"report",a);return ResponseEntity.ok().header("Content-Disposition","attachment; filename=agentlens-"+id+".html").body(str(r,"html"));}
    @GetMapping("/audit")List<ObjectNode> audit(@RequestParam String project_id,Authentication a){p.project(project_id,a,"ADMIN");List<ObjectNode> records=p.store.list("audit",project_id);Collections.reverse(records);return records;}
}
