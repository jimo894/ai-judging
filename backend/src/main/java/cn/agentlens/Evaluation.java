package cn.agentlens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static cn.agentlens.Json.*;

public final class Evaluation {
    public static final String VERSION="business-rules-v1";
    public static ObjectNode score(JsonNode c,JsonNode response) {
        JsonNode checks=c.path("checks");
        if(checks.isMissingNode() || checks.isEmpty()) return verdict("UNKNOWN",null,"缺少业务判定规则，等待人工审核");
        if(checks.path("manual").asBoolean()) return verdict("UNKNOWN",null,"此案例要求人工审核业务依据");
        if(!checks.has("contains")&&!checks.has("not_contains")&&!checks.has("equals")&&!checks.has("tool"))return verdict("UNKNOWN",null,"没有可执行的业务规则");
        String output=str(response,"output");
        List<String> failures=new ArrayList<>();
        for(JsonNode word:checks.path("contains")) if(!output.contains(word.asText())) failures.add("回答缺少业务要点："+word.asText());
        for(JsonNode word:checks.path("not_contains")) if(output.contains(word.asText())) failures.add("回答包含禁止内容："+word.asText());
        if(checks.has("equals")&&!output.equals(checks.path("equals").asText())) failures.add("回答与精确检查值不一致");
        boolean unknown=false;
        if(checks.has("tool")) {
            JsonNode trace=response.path("trace");
            if(!trace.isArray()||trace.isEmpty()||!response.path("trace_complete").asBoolean()) unknown=true;
            else {
                List<JsonNode> matching=new ArrayList<>();
                for(JsonNode span:trace) if(str(span,"type").equals("tool")&&str(span,"name").equals(checks.path("tool").asText())) matching.add(span);
                if(matching.isEmpty()) failures.add("未调用必需工具："+checks.path("tool").asText());
                else if(checks.has("tool_args")) {
                    boolean valid=matching.stream().anyMatch(span->{Iterator<String> keys=checks.path("tool_args").fieldNames();while(keys.hasNext()){String k=keys.next();if(!checks.path("tool_args").get(k).equals(span.path("args").path(k)))return false;}return true;});
                    if(!valid) failures.add("工具参数不符合业务约束");
                }
            }
        }
        if(!failures.isEmpty()) return verdict("SCORED",false,String.join("；",failures));
        if(unknown) return verdict("UNKNOWN",null,"工具 trace 缺失或不完整，无法判断工具正确性");
        return verdict("SCORED",true,"通过本案例全部业务规则；规则通过不代表已验证证据忠实度");
    }
    public static ObjectNode verdict(String state,Boolean passed,String reason) {
        return obj("id",id("score"),"state",state,"passed",passed,"reason",reason,"evaluator_version",VERSION,"source","RULE","created_at",now());
    }
    public static ObjectNode metrics(List<ObjectNode> results) {
        int pass=0,fail=0,unknown=0,pending=0,scoreErrors=0,callErrors=0,cancelled=0,success=0,attempted=0,done=0,known=0,totalAttempts=0;
        double cost=0;
        List<Double> latency=new ArrayList<>();
        List<Double> errorLatency=new ArrayList<>();
        for(JsonNode r:results) {
            String status=str(r,"status");
            if(!status.equals("PENDING")&&!status.equals("RUNNING")) done++;
            if(status.equals("SUCCEEDED")) success++;
            if(status.equals("CALL_ERROR")||status.equals("PLATFORM_ERROR")||status.equals("NEEDS_REVIEW")) callErrors++;
            if(status.equals("CANCELLED")) cancelled++;
            JsonNode s=r.path("score");
            switch(str(s,"state")) {
                case "SCORED" -> {if(s.path("passed").asBoolean())pass++;else fail++;}
                case "UNKNOWN" -> unknown++;
                case "ERROR" -> scoreErrors++;
                default -> pending++;
            }
            if(!r.path("attempts").isEmpty()) attempted++;
            for(JsonNode a:r.path("attempts")) {
                totalAttempts++;
                if(a.hasNonNull("cost")) {known++;cost+=a.path("cost").asDouble();}
                if(a.has("duration_ms")) (str(a,"status").equals("SUCCEEDED")?latency:errorLatency).add(a.path("duration_ms").asDouble());
            }
        }
        int judged=pass+fail,n=results.size();
        return obj("total",n,"done",done,"pass",pass,"fail",fail,"unknown",unknown,"pending",pending,"score_errors",scoreErrors,"call_errors",callErrors,"cancelled",cancelled,
            "success",success,"attempted",attempted,"pass_rate",ratio(pass,judged),"coverage",ratio(judged,n),"all_pass_rate",ratio(pass,n),"reliability",ratio(success,attempted),
            "p50_ms",percentile(latency,0.50),"p95_ms",percentile(latency,0.95),"latency_samples",latency.size(),"error_p95_ms",percentile(errorLatency,0.95),
            "known_cost",known==0?null:cost,"cost_coverage",ratio(known,totalAttempts),"attempt_count",totalAttempts,"currency","CNY","judge","NOT_ENABLED");
    }
    static Double ratio(int a,int b) { return b==0?null:(double)a/b; }
    static Double percentile(List<Double> values,double q) { if(values.isEmpty())return null;values.sort(Double::compare);return values.get(Math.max(0,(int)Math.ceil(values.size()*q)-1)); }
}
