package cn.agentlens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.agentlens.Json.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:integration;MODE=MySQL;DB_CLOSE_DELAY=-1","agentlens.mock-delay-ms=2"})
class PlatformIntegrationTest {
 @LocalServerPort int port;
 @Autowired Platform p;
 private final HttpClient http=HttpClient.newHttpClient();
 private HttpResponse<String> request(String user,String method,String path,JsonNode body,String key)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":Demo2026!").getBytes(StandardCharsets.UTF_8))).header("Content-Type","application/json");
  if(key!=null)b.header("Idempotency-Key",key);
  return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
 }
 private JsonNode json(HttpResponse<String> r){assertThat(r.statusCode()).isBetween(200,299);return parse(r.body());}
 private JsonNode awaitRun(String id)throws Exception{for(int i=0;i<100;i++){var r=json(request("admin","GET","/runs/"+id,null,null));if(!Set.of("QUEUED","RUNNING").contains(str(r,"status")))return r;Thread.sleep(100);}throw new AssertionError("run timed out");}
 @Test void completeWorkflowAndReportSnapshot()throws Exception{
  var req=obj("project_id","project-demo","dataset_id","dataset-demo-v1","agent_id","agent-demo-A","name","A test","max_requests",50,"max_retries",0);
  String key=UUID.randomUUID().toString();var a=json(request("admin","POST","/runs",req,key));
  assertThat(json(request("admin","POST","/runs",req,key)).path("id")).isEqualTo(a.path("id"));
  req.put("name","changed");assertThat(request("admin","POST","/runs",req,key).statusCode()).isEqualTo(409);
  req.put("agent_id","agent-demo-B");var b=json(request("admin","POST","/runs",req,UUID.randomUUID().toString()));
  var av=awaitRun(str(a,"id"));var bv=awaitRun(str(b,"id"));
  assertThat(av.path("results").size()).isEqualTo(25);assertThat(bv.path("metrics").path("call_errors").asInt()).isEqualTo(1);
  var comparison=json(request("admin","GET","/comparisons?baseline_run_id="+str(a,"id")+"&candidate_run_id="+str(b,"id"),null,null));
  assertThat(comparison.path("improved").asInt()).isEqualTo(6);assertThat(comparison.path("regressed").asInt()).isEqualTo(1);assertThat(comparison.path("incomparable").asInt()).isEqualTo(3);
  var report=json(request("admin","POST","/reports",obj("baseline_run_id",str(a,"id"),"candidate_run_id",str(b,"id"),"mode","redacted"),null));
  String html=request("admin","GET","/reports/"+str(report,"id")+"/html",null,null).body();
  assertThat(html).contains("SIMULATED","覆盖率").doesNotContain("<script", "购买后多久", "客服助手 v1.0");
  JsonNode result=bv.path("results").get(0);var review=obj("verdict","FAIL","reason","人工复核测试，保留历史");
  assertThat(request("developer","POST","/results/"+str(result,"id")+"/reviews",review,null).statusCode()).isEqualTo(403);
  var revised=json(request("reviewer","POST","/results/"+str(result,"id")+"/reviews",review,null));assertThat(revised.path("score_history").size()).isEqualTo(1);
  assertThat(request("admin","GET","/reports/"+str(report,"id")+"/html",null,null).body()).isEqualTo(html);
 }
 @Test void projectIsolationAndImmutableDataset()throws Exception{
  assertThat(request("outsider","GET","/datasets?project_id=project-demo",null,null).statusCode()).isEqualTo(403);
  assertThat(request("admin","GET","/datasets?project_id=project-private",null,null).statusCode()).isEqualTo(403);
  var invalid=obj("project_id","project-demo","name","invalid","jsonl","{\"case_id\":\"x\",\"input\":\"q\"}\n{\"case_id\":\"x\",\"input\":\"q\"}");
  var error=request("admin","POST","/datasets/import",invalid,null);assertThat(error.statusCode()).isEqualTo(400);assertThat(parse(error.body()).path("details").get(0).path("line").asInt()).isEqualTo(2);
  invalid.put("jsonl","{\"case_id\":\"x\",\"input\":\"<script>alert(1)</script>\",\"checks\":{\"contains\":[\"ok\"]}}");
  var d=json(request("admin","POST","/datasets/import",invalid,null));json(request("admin","POST","/datasets/"+str(d,"id")+"/publish",null,null));assertThat(request("admin","POST","/datasets/"+str(d,"id")+"/publish",null,null).statusCode()).isEqualTo(409);
 }
 @Test void endpointAllowlistRejectsMetadata()throws Exception{
  var r=request("admin","POST","/agents",obj("project_id","project-demo","name","unsafe","adapter","HTTP","endpoint","http://169.254.169.254/latest/meta-data"),null);assertThat(r.statusCode()).isEqualTo(400);
 }
 @Test void requestBudgetAndCancellationPersist()throws Exception{
  var req=obj("project_id","project-demo","dataset_id","dataset-demo-v1","agent_id","agent-demo-A","max_requests",1);
  var r=json(request("admin","POST","/runs",req,UUID.randomUUID().toString()));var complete=awaitRun(str(r,"id"));assertThat(complete.path("request_count").asInt()).isEqualTo(1);assertThat(complete.path("metrics").path("cancelled").asInt()).isEqualTo(24);
  synchronized(p){
   var auth=new UsernamePasswordAuthenticationToken("admin","unused");var cancelled=p.createRun(req,UUID.randomUUID().toString(),auth);p.cancel(str(cancelled,"id"),auth);
   assertThat(p.store.get(str(cancelled,"id"),"run").path("cancel_requested").asBoolean()).isTrue();
  }
 }
 @Test void traceDeduplicationConflictAndMissingParent()throws Exception{
  var req=obj("project_id","project-demo","dataset_id","dataset-demo-v1","agent_id","agent-demo-B","max_requests",1);
  var run=json(request("admin","POST","/runs",req,UUID.randomUUID().toString()));var done=awaitRun(str(run,"id"));JsonNode result=done.path("results").get(0);
  var step=obj("span_id","late-child","parent_span_id","absent","type","tool","name","lookup","duration_ms",3);
  var input=obj("result_id",str(result,"id"),"steps",arr().add(step));var first=json(request("admin","POST","/traces/batch",input,null));assertThat(first.path("complete").asBoolean()).isFalse();
  var repeat=json(request("admin","POST","/traces/batch",input,null));assertThat(repeat.path("results").get(0).path("status").asText()).isEqualTo("DUPLICATE");
  step.put("name","different");input.set("steps",arr().add(step));assertThat(json(request("admin","POST","/traces/batch",input,null)).path("results").get(0).path("status").asText()).isEqualTo("REJECTED");
 }
}
