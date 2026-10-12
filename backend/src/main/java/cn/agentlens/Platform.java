package cn.agentlens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.Authentication;
import java.util.*;
import static cn.agentlens.Json.*;

@Service
public class Platform {
    public final Store store;
    private final TransactionTemplate tx;
    private final AgentClient client;
    public Platform(Store store,TransactionTemplate tx,AgentClient client){this.store=store;this.tx=tx;this.client=client;}
    public ObjectNode project(String project,Authentication auth,String... roles) {
        ObjectNode p=store.get(project,"project");
        String role=p.path("members").path(auth.getName()).asText();
        check(!role.isEmpty(),403,"PROJECT_ACCESS","你没有此项目的访问权限");
        if(roles.length>0)check(Arrays.asList(roles).contains(role),403,"ROLE_ACCESS","当前角色无权执行此操作");
        return p;
    }
    public ObjectNode access(String id,String kind,Authentication auth,String... roles) {
        ObjectNode n=store.get(id,kind);project(str(n,"project_id"),auth,roles);return n;
    }
    public List<ObjectNode> projects(Authentication auth){return store.all("project").stream().filter(p->p.path("members").has(auth.getName())).toList();}
    public synchronized ObjectNode createProject(JsonNode input,Authentication auth) {
        String id=id("project");return store.insert("project",id,obj("id",id,"name",required(input,"name"),"members",obj(auth.getName(),"ADMIN")));
    }
    public synchronized ObjectNode member(String id,JsonNode input,Authentication auth) {
        ObjectNode p=project(id,auth,"ADMIN");String user=required(input,"username"),role=required(input,"role");
        check(Set.of("admin","developer","reviewer","outsider").contains(user),400,"USER","账号不存在");
        check(Set.of("ADMIN","DEVELOPER","REVIEWER","VIEWER").contains(role),400,"ROLE","角色无效");
        check(!user.equals(auth.getName())||role.equals("ADMIN"),409,"LAST_ADMIN","不能修改自己的管理员权限");
        ((ObjectNode)p.path("members")).put(user,role);store.save(p);audit(id,auth.getName(),"MEMBER_UPDATED",user);return p;
    }
    public synchronized ObjectNode importDataset(JsonNode input,Authentication auth) {
        String project=required(input,"project_id");project(project,auth,"ADMIN","DEVELOPER","REVIEWER");
        String text=str(input,"jsonl");check(!text.isBlank()&&text.length()<=500000,400,"SIZE","导入内容不能为空且不能超过 500 KB");
        ArrayNode errors=arr(),cases=arr();Set<String> seen=new HashSet<>();String[] lines=text.split("\\R");
        for(int i=0;i<lines.length;i++) {
            if(lines[i].isBlank())continue;
            try {
                JsonNode c=parse(lines[i]);check(c.isObject(),400,"CASE","每行必须是 JSON 对象");
                String cid=required(c,"case_id");required(c,"input");
                check(cid.length()<=80,400,"CASE","case_id 过长");
                check(seen.add(cid),400,"DUPLICATE","case_id 重复："+cid);
                if(c.has("checks")) {
                    check(c.path("checks").isObject(),400,"CHECKS","checks 必须是对象");
                    for(String key:List.of("contains","not_contains"))if(c.path("checks").has(key))check(c.path("checks").path(key).isArray()&&!c.path("checks").path(key).isEmpty()&&isTextArray(c.path("checks").path(key)),400,"CHECKS",key+" 必须是非空字符串数组");
                    if(c.path("checks").has("equals"))check(c.path("checks").path("equals").isTextual(),400,"CHECKS","equals 必须是文本");
                    if(c.path("checks").has("manual"))check(c.path("checks").path("manual").isBoolean(),400,"CHECKS","manual 必须为布尔值");
                    if(c.path("checks").has("tool"))check(c.path("checks").path("tool").isTextual()&&!str(c.path("checks"),"tool").isBlank(),400,"CHECKS","tool 必须为非空文本");
                    Set<String> keys=Set.of("contains","not_contains","equals","manual","tool","tool_args");
                    c.path("checks").fieldNames().forEachRemaining(k->check(keys.contains(k),400,"CHECKS","不支持的规则："+k));
                    if(c.path("checks").has("tool_args"))check(c.path("checks").path("tool_args").isObject()&&c.path("checks").has("tool"),400,"CHECKS","工具参数必须配合 tool 规则");
                }
                cases.add(c);
            }catch(ApiError e){errors.add(obj("line",i+1,"message",e.getMessage()));}
        }
        check(cases.size()<=1000,400,"SIZE","单个数据集最多 1000 条");
        if(!errors.isEmpty())throw new ApiError(400,"IMPORT_ERRORS","请修正导入错误后重新提交",errors);
        check(!cases.isEmpty(),400,"EMPTY","问题集不能为空");
        ObjectNode d=store.insert("dataset",project,obj("name",required(input,"name"),"status","DRAFT","cases",cases,"source",input.path("source").asText("用户导入，来源待审核")));
        audit(project,auth.getName(),"DATASET_IMPORTED",str(d,"id"));return d;
    }
    private boolean isTextArray(JsonNode n){for(JsonNode v:n)if(!v.isTextual()||v.asText().isBlank())return false;return true;}
    public synchronized ObjectNode publish(String id,Authentication auth) {
        ObjectNode d=access(id,"dataset",auth,"ADMIN","DEVELOPER","REVIEWER");
        check(str(d,"status").equals("DRAFT"),409,"IMMUTABLE","已发布版本不可原地修改，请重新导入新版本");
        d.put("status","PUBLISHED").put("content_hash",hash(d.path("cases"))).put("published_at",now());store.save(d);audit(str(d,"project_id"),auth.getName(),"DATASET_PUBLISHED",id);return d;
    }
    public synchronized ObjectNode createAgent(JsonNode input,Authentication auth) {
        String project=required(input,"project_id");project(project,auth,"ADMIN");
        String adapter=required(input,"adapter");check(Set.of("HTTP","MOCK").contains(adapter),400,"ADAPTER","接入类型无效");
        int timeout=input.path("timeout_seconds").asInt(60);check(timeout>=1&&timeout<=60,400,"TIMEOUT","超时范围为 1–60 秒");
        ObjectNode config=obj("name",required(input,"name"),"adapter",adapter,"variant",input.path("variant").asText("B"),"endpoint",str(input,"endpoint"),"secret_ref",str(input,"secret_ref"),"timeout_seconds",timeout,"idempotent",input.path("idempotent").asBoolean(false),"capability",input.path("capability").asText("black_box"),"input_price",input.get("input_price"),"output_price",input.get("output_price"),"price_version",input.path("price_version").asText("unknown"),"currency","CNY");
        check(Set.of("A","B").contains(str(config,"variant")),400,"VARIANT","模拟版本必须为 A 或 B");
        for(String k:List.of("input_price","output_price"))if(config.hasNonNull(k))check(config.path(k).isNumber()&&config.path(k).asDouble()>=0,400,"PRICE","价格必须为非负数字，单位为元/百万 Token");
        client.validate(config);config.put("config_hash",hash(config));ObjectNode saved=store.insert("agent",project,config);audit(project,auth.getName(),"AGENT_CREATED",str(saved,"id"));return saved;
    }
    public synchronized ObjectNode createRun(JsonNode input,String key,Authentication auth) {
        String project=required(input,"project_id");project(project,auth,"ADMIN","DEVELOPER");
        check(key!=null&&!key.isBlank()&&key.length()<=100,400,"IDEMPOTENCY","请提供有效 Idempotency-Key");
        String fingerprint=hash(input);
        for(ObjectNode run:store.list("run",project))if(str(run,"idempotency_key").equals(key)) {check(str(run,"request_hash").equals(fingerprint),409,"IDEMPOTENCY_CONFLICT","相同幂等键的请求内容不一致");return run;}
        ObjectNode dataset=access(required(input,"dataset_id"),"dataset",auth),agent=access(required(input,"agent_id"),"agent",auth);
        check(str(dataset,"project_id").equals(project)&&str(agent,"project_id").equals(project),403,"PROJECT_ACCESS","数据集、Agent 与批次必须属于同一项目");
        check(str(dataset,"status").equals("PUBLISHED"),409,"UNPUBLISHED","请先发布问题集版本");
        int budget=input.path("max_requests").asInt(dataset.path("cases").size()*2);check(budget>=1&&budget<=2000,400,"BUDGET","请求预算应在 1–2000 次之间");
        int retries=input.path("max_retries").asInt(0);check(retries>=0&&retries<=1,400,"RETRIES","首版最多重试 1 次");
        ObjectNode manifest=obj("dataset_id",str(dataset,"id"),"dataset_hash",str(dataset,"content_hash"),"agent_id",str(agent,"id"),"agent_name",str(agent,"name"),"agent_config_hash",str(agent,"config_hash"),"evaluator_version",Evaluation.VERSION,"price_version",str(agent,"price_version"),"model_version","unknown","prompt_version","unknown","knowledge_version","unknown","limitations","外部模型、提示词和知识库版本未取得；首版为规则与人工评分，未启用模型裁判","source",str(agent,"adapter").equals("MOCK")?"SIMULATED":"EXTERNAL","max_requests",budget,"max_retries",retries);
        return tx.execute(status->{
            ObjectNode run=store.insert("run",project,obj("name",input.path("name").asText(str(agent,"name")+" · 评测"),"dataset_id",str(dataset,"id"),"agent_id",str(agent,"id"),"status","QUEUED","manifest",manifest,"manifest_hash",hash(manifest),"idempotency_key",key,"request_hash",fingerprint,"requested_by",auth.getName(),"max_requests",budget,"max_retries",retries,"request_count",0,"cancel_requested",false));
            for(JsonNode c:dataset.path("cases"))store.insert("result",project,obj("run_id",str(run,"id"),"case_id",str(c,"case_id"),"case",c,"status","PENDING","attempts",arr(),"score",Evaluation.verdict("PENDING",null,"等待执行"),"score_history",arr()));
            audit(project,auth.getName(),"RUN_CREATED",str(run,"id"));return run;
        });
    }
    public List<ObjectNode> results(String runId) {ObjectNode r=store.get(runId,"run");return store.list("result",str(r,"project_id")).stream().filter(n->str(n,"run_id").equals(runId)).toList();}
    public synchronized ObjectNode runView(String id,Authentication auth) {
        ObjectNode r=access(id,"run",auth);List<ObjectNode> results=results(id);r.set("metrics",Evaluation.metrics(results));r.set("results",M.valueToTree(results));return r;
    }
    public synchronized List<ObjectNode> runs(String project,Authentication auth) {
        project(project,auth);List<ObjectNode> runs=store.list("run",project);for(ObjectNode r:runs)r.set("metrics",Evaluation.metrics(results(str(r,"id"))));Collections.reverse(runs);return runs;
    }
    public synchronized ObjectNode cancel(String id,Authentication auth) {
        ObjectNode r=access(id,"run",auth,"ADMIN","DEVELOPER");check(Set.of("QUEUED","RUNNING").contains(str(r,"status")),409,"STATE","此批次已结束");
        r.put("cancel_requested",true);store.save(r);audit(str(r,"project_id"),auth.getName(),"RUN_CANCEL_REQUESTED",id);return r;
    }
    public synchronized ObjectNode review(String id,JsonNode input,Authentication auth) {
        ObjectNode r=access(id,"result",auth,"ADMIN","REVIEWER");check(str(r,"status").equals("SUCCEEDED"),409,"STATE","仅已成功取得回答的案例可以复核");
        String verdict=required(input,"verdict"),reason=required(input,"reason");check(Set.of("PASS","FAIL","UNKNOWN").contains(verdict),400,"VERDICT","判定无效");
        ObjectNode score=Evaluation.verdict(verdict.equals("UNKNOWN")?"UNKNOWN":"SCORED",verdict.equals("UNKNOWN")?null:verdict.equals("PASS"),reason);
        score.put("source","HUMAN").put("reviewer",auth.getName()).put("supersedes_id",r.path("score").path("id").asText());
        ((ArrayNode)r.path("score_history")).add(r.path("score"));r.set("score",score);store.save(r);audit(str(r,"project_id"),auth.getName(),"RESULT_REVIEWED",id);return r;
    }
    public synchronized ObjectNode comparison(String baseline,String candidate,Authentication auth) {
        check(!baseline.equals(candidate),422,"NOT_COMPARABLE","请选择两个不同的评测批次");
        ObjectNode a=access(baseline,"run",auth),b=access(candidate,"run",auth);
        check(str(a,"project_id").equals(str(b,"project_id")),422,"NOT_COMPARABLE","不能比较不同项目的运行");
        check(a.path("manifest").path("dataset_hash").equals(b.path("manifest").path("dataset_hash"))&&a.path("manifest").path("evaluator_version").equals(b.path("manifest").path("evaluator_version")),422,"NOT_COMPARABLE","数据集内容或评分版本不同，不能生成正式差值");
        check(!Set.of("QUEUED","RUNNING").contains(str(a,"status"))&&!Set.of("QUEUED","RUNNING").contains(str(b,"status")),409,"RUNNING","请等待两个批次结束再比较");
        List<ObjectNode> ar=results(baseline),br=results(candidate);Map<String,ObjectNode> bm=new HashMap<>();br.forEach(r->bm.put(str(r,"case_id"),r));
        ArrayNode rows=arr();int improved=0,regressed=0,same=0,incomparable=0,ap=0,bp=0;
        for(ObjectNode left:ar) {
            ObjectNode right=bm.get(str(left,"case_id"));String change="INCOMPARABLE",reason="至少一版尚未完成有效判定";
            if(right!=null&&str(left.path("score"),"state").equals("SCORED")&&str(right.path("score"),"state").equals("SCORED")) {
                boolean av=left.path("score").path("passed").asBoolean(),bv=right.path("score").path("passed").asBoolean();
                if(av)ap++;if(bv)bp++;reason="同问题集、同规则版本，双方均已判定";
                if(!av&&bv){change="IMPROVED";improved++;}else if(av&&!bv){change="REGRESSED";regressed++;}else{change="UNCHANGED";same++;}
            }else incomparable++;
            rows.add(obj("case_id",str(left,"case_id"),"input",left.path("case").path("input"),"change",change,"reason",reason,"baseline",left,"candidate",right));
        }
        int pairs=improved+regressed+same;
        return obj("baseline",a,"candidate",b,"baseline_metrics",Evaluation.metrics(ar),"candidate_metrics",Evaluation.metrics(br),"improved",improved,"regressed",regressed,"unchanged",same,"incomparable",incomparable,"paired",pairs,"baseline_paired_rate",Evaluation.ratio(ap,pairs),"candidate_paired_rate",Evaluation.ratio(bp,pairs),"delta",pairs==0?null:(double)(bp-ap)/pairs,"rows",rows);
    }
    public synchronized ObjectNode acceptTrace(JsonNode input,Authentication auth) {
        String resultId=required(input,"result_id");ObjectNode r=access(resultId,"result",auth,"ADMIN","DEVELOPER");
        check(input.path("steps").isArray()&&input.path("steps").size()<=100,400,"TRACE","steps 必须是最多 100 条的数组");
        ArrayNode current=(ArrayNode)r.withArray("trace");ArrayNode feedback=arr();
        for(JsonNode step:input.path("steps")) {
            try {
                String span=required(step,"span_id");required(step,"name");check(Set.of("agent","model","retrieval","tool").contains(str(step,"type")),400,"TRACE_TYPE","步骤类型无效");
                check(step.path("duration_ms").isNumber()&&step.path("duration_ms").asDouble()>=0,400,"TRACE_DURATION","步骤耗时无效");
                check(step.toString().length()<=20000,400,"TRACE_SIZE","步骤内容过大");
                JsonNode old=null;for(JsonNode v:current)if(str(v,"span_id").equals(span))old=v;
                if(old!=null)check(old.equals(step),409,"SPAN_CONFLICT","同 span_id 内容冲突");else current.add(step);
                feedback.add(obj("span_id",span,"status",old==null?"ACCEPTED":"DUPLICATE"));
            }catch(ApiError e){feedback.add(obj("span_id",str(step,"span_id"),"status","REJECTED","reason",e.getMessage()));}
        }
        Set<String> ids=new HashSet<>();current.forEach(v->ids.add(str(v,"span_id")));boolean complete=!current.isEmpty();
        for(JsonNode s:current)if(s.hasNonNull("parent_span_id")&&!ids.contains(str(s,"parent_span_id")))complete=false;
        r.put("trace_complete",complete);store.save(r);audit(str(r,"project_id"),auth.getName(),"TRACE_RECEIVED",resultId);return obj("results",feedback,"complete",complete);
    }
    public void audit(String project,String actor,String action,String target){store.insert("audit",project,obj("actor",actor,"action",action,"target",target));}
}
