package cn.agentlens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static cn.agentlens.Json.*;

@RestController
public class MockAgent {
    private final boolean enabled; private final int delay;
    public MockAgent(@Value("${agentlens.demo}") boolean enabled,@Value("${agentlens.mock-delay-ms}")int delay){this.enabled=enabled;this.delay=delay;}
    @PostMapping("/mock/{variant}")
    public ResponseEntity<JsonNode> call(@PathVariable String variant,@RequestBody JsonNode body) throws InterruptedException {
        check(enabled,404,"NOT_FOUND","模拟 Agent 已关闭");
        check(variant.equals("A")||variant.equals("B"),404,"NOT_FOUND","模拟版本不存在");
        Thread.sleep(delay);
        JsonNode c=body.path("demo_case");
        int index=c.path("demo_index").asInt(1);
        if(index==24)return ResponseEntity.status(503).body(obj("message","模拟上游不可用（故障注入）"));
        boolean wrong=variant.equals("A")?(index<=6||index==21):(index==7||index==21);
        String output=wrong?"这项业务没有限制，可以直接办理。":c.path("reference").asText("模拟连接测试成功");
        ArrayNode trace=arr();
        trace.add(obj("span_id","root","parent_span_id",null,"type","agent","name","客服 Agent · "+variant,"duration_ms",delay,"status","OK"));
        trace.add(obj("span_id","retrieval","parent_span_id","root","type","retrieval","name","检索业务知识库","duration_ms",Math.max(1,delay/3),"status","OK","doc_refs",c.path("evidence")));
        if(c.path("checks").has("tool")) trace.add(obj("span_id","tool","parent_span_id","root","type","tool","name",c.path("checks").path("tool"),"duration_ms",Math.max(1,delay/4),"status","OK","args",index==21?obj("order_id","WRONG-ID"):c.path("checks").path("tool_args")));
        trace.add(obj("span_id","model","parent_span_id","root","type","model","name","模拟语言模型","duration_ms",Math.max(1,delay/2),"status","OK"));
        ObjectNode result=obj("output",output,"trace",index==23?arr():trace,"trace_complete",index!=23,"source","SIMULATED");
        if(index!=25)result.set("usage",obj("input_tokens",120+index*7,"output_tokens",output.length()+25));
        return ResponseEntity.ok(result);
    }
}
