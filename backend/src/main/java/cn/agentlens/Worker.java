package cn.agentlens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static cn.agentlens.Json.*;

@Component
public class Worker {
    private final Platform platform;private final Store store;private final AgentClient client;private final TransactionTemplate tx;
    private final ExecutorService pool=Executors.newFixedThreadPool(2);private final AtomicInteger active=new AtomicInteger();
    private final boolean enabled;private volatile int port;
    public Worker(Platform p,AgentClient c,TransactionTemplate tx,@Value("${agentlens.worker-enabled}")boolean enabled){platform=p;store=p.store;client=c;this.tx=tx;this.enabled=enabled;}
    @EventListener public void server(WebServerInitializedEvent e){port=e.getWebServer().getPort();}
    @PreDestroy public void close(){pool.shutdownNow();}
    @Scheduled(fixedDelay=250)
    public void tick(){
        if(!enabled||port==0)return;
        synchronized(platform){
            for(ObjectNode run:store.all("run")) {
                if(!Set.of("QUEUED","RUNNING").contains(str(run,"status")))continue;
                recover(run);List<ObjectNode> results=platform.results(str(run,"id"));
                for(ObjectNode r:results) {
                    if(!str(r,"status").equals("PENDING"))continue;
                    if(r.has("retry_after")&&Instant.parse(str(r,"retry_after")).isAfter(Instant.now())&&!run.path("cancel_requested").asBoolean())continue;
                    if(run.path("cancel_requested").asBoolean()||run.path("request_count").asInt()>=run.path("max_requests").asInt()) {
                        r.put("status","CANCELLED").put("error_code",run.path("cancel_requested").asBoolean()?"USER_CANCELLED":"REQUEST_BUDGET");
                        r.set("score",Evaluation.verdict("UNKNOWN",null,"未发起调用：取消或请求预算耗尽"));store.save(r);continue;
                    }
                    if(active.get()>=2)break;
                    ObjectNode agent=store.get(str(run,"agent_id"),"agent");String lease=id("lease");
                    tx.executeWithoutResult(s->{
                        r.put("status","RUNNING").put("lease_token",lease).put("lease_until",Instant.now().plusSeconds(agent.path("timeout_seconds").asInt(60)+15).toString());
                        ((ArrayNode)r.path("attempts")).add(obj("number",r.path("attempts").size()+1,"started_at",now(),"status","RUNNING","request_id",str(r,"id")));
                        store.save(r);run.put("status","RUNNING").put("request_count",run.path("request_count").asInt()+1);store.save(run);
                    });
                    active.incrementAndGet();pool.submit(()->execute(str(r,"id"),lease,agent,r.path("case")));
                }
                finish(run);
            }
        }
    }
    private void recover(ObjectNode run){
        for(ObjectNode r:platform.results(str(run,"id")))if(str(r,"status").equals("RUNNING")&&Instant.parse(str(r,"lease_until")).isBefore(Instant.now())){
            ObjectNode agent=store.get(str(run,"agent_id"),"agent");
            ArrayNode attempts=(ArrayNode)r.path("attempts");if(!attempts.isEmpty())((ObjectNode)attempts.get(attempts.size()-1)).put("status","INTERRUPTED").put("finished_at",now());
            boolean safe=agent.path("idempotent").asBoolean()||str(agent,"adapter").equals("MOCK");
            boolean retry=safe&&attempts.size()<=run.path("max_retries").asInt()&&run.path("request_count").asInt()<run.path("max_requests").asInt();
            r.put("status",retry?"PENDING":"NEEDS_REVIEW").put("error_code","INTERRUPTED");
            r.set("score",Evaluation.verdict(retry?"PENDING":"UNKNOWN",null,retry?"中断后按幂等策略恢复":"调用中断，外部执行情况待核对"));store.save(r);
        }
    }
    private void execute(String resultId,String lease,JsonNode agent,JsonNode input){
        long start=System.nanoTime();ObjectNode response=null;String error=null;boolean retryable=false;
        try {response=client.call(agent,input,resultId,port);}catch(AgentClient.CallFailure e){error=e.code;retryable=e.retryable;}catch(java.net.http.HttpTimeoutException e){error="TIMEOUT";retryable=true;}catch(Exception e){error="CONNECTION_OR_PROTOCOL_ERROR";retryable=e instanceof java.io.IOException;}
        double duration=(System.nanoTime()-start)/1_000_000.0;
        try {
            synchronized(platform){
                ObjectNode r=store.get(resultId,"result");if(!str(r,"lease_token").equals(lease)||!str(r,"status").equals("RUNNING"))return;
                ObjectNode run=store.get(str(r,"run_id"),"run");ArrayNode attempts=(ArrayNode)r.path("attempts");ObjectNode a=(ObjectNode)attempts.get(attempts.size()-1);
                a.put("finished_at",now()).put("duration_ms",duration).put("status",error==null?"SUCCEEDED":"CALL_ERROR");
                if(error==null){
                    r.put("status","SUCCEEDED").put("output",str(response,"output")).put("duration_ms",duration).put("source",str(response,"source"));
                    r.set("trace",response.path("trace"));r.put("trace_complete",response.path("trace_complete").asBoolean());
                    JsonNode usage=response.path("usage");r.set("usage",usage);a.set("usage",usage);
                    if(usage.path("input_tokens").isNumber()&&usage.path("output_tokens").isNumber()&&usage.path("input_tokens").asDouble()>=0&&usage.path("output_tokens").asDouble()>=0&&agent.hasNonNull("input_price")&&agent.hasNonNull("output_price"))a.put("cost",(usage.path("input_tokens").asDouble()*agent.path("input_price").asDouble()+usage.path("output_tokens").asDouble()*agent.path("output_price").asDouble())/1_000_000.0);
                    try{r.set("score",Evaluation.score(input,response));}catch(Exception e){r.set("score",Evaluation.verdict("ERROR",null,"规则执行异常，请人工核查"));}
                } else {
                    a.put("error_code",error);boolean safe=agent.path("idempotent").asBoolean()||str(agent,"adapter").equals("MOCK");
                    boolean retry=retryable&&safe&&attempts.size()<=run.path("max_retries").asInt()&&!run.path("cancel_requested").asBoolean()&&run.path("request_count").asInt()<run.path("max_requests").asInt();
                    r.put("status",retry?"PENDING":"CALL_ERROR").put("error_code",error);r.set("score",Evaluation.verdict(retry?"PENDING":"UNKNOWN",null,retry?"暂时性调用错误，等待有限重试":"Agent 调用错误，未取得可判定回答"));
                    if(retry)r.put("retry_after",Instant.now().plusSeconds(1).toString());
                }
                r.remove("lease_token");r.remove("lease_until");store.save(r);finish(run);
            }
        }finally{active.decrementAndGet();}
    }
    private void finish(ObjectNode run){
        List<ObjectNode> rs=platform.results(str(run,"id"));
        if(rs.stream().anyMatch(r->Set.of("PENDING","RUNNING").contains(str(r,"status"))))return;
        boolean errors=rs.stream().anyMatch(r->!str(r,"status").equals("SUCCEEDED"));
        run.put("status",run.path("cancel_requested").asBoolean()?"CANCELLED":errors?"COMPLETED_WITH_ERRORS":"COMPLETED").put("finished_at",now());store.save(run);
    }
}
