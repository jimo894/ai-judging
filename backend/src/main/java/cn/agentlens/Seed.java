package cn.agentlens;

import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import static cn.agentlens.Json.*;

@Component
public class Seed implements ApplicationRunner {
    private final Store store;private final boolean demo;private final JdbcTemplate jdbc;
    public Seed(Store store,JdbcTemplate jdbc,@Value("${agentlens.demo}")boolean demo){this.store=store;this.jdbc=jdbc;this.demo=demo;}
    @Override public void run(ApplicationArguments args)throws Exception{
        if(jdbc.queryForObject("SELECT COUNT(*) FROM schema_history WHERE version='001'",Integer.class)==0)jdbc.update("INSERT INTO schema_history(version,description) VALUES('001','Persistent document snapshots for single-instance demo')");
        if(store.find("project-demo").isPresent())return;
        store.insert("project","project-demo",obj("id","project-demo","name","星云科技 · 客服知识库","description","客服 Agent 版本回归评测","members",obj("admin","ADMIN","developer","DEVELOPER","reviewer","REVIEWER")));
        store.insert("project","project-private",obj("id","project-private","name","隔离验证项目","members",obj("outsider","VIEWER")));
        if(!demo)return;
        ArrayNode cases=arr();
        try(var stream=new ClassPathResource("demo-cases.jsonl").getInputStream()){for(String line:new String(stream.readAllBytes(),StandardCharsets.UTF_8).split("\\R"))if(!line.isBlank())cases.add(parse(line));}
        store.insert("dataset","project-demo",obj("id","dataset-demo-v1","name","企业客服 · 业务验收集","status","PUBLISHED","cases",cases,"content_hash",hash(cases),"source","自建模拟业务数据，非企业实测","published_at",now()));
        for(String variant:new String[]{"A","B"}){
            ObjectNode config=obj("id","agent-demo-"+variant,"name",variant.equals("A")?"客服助手 v1.0 · 基线":"客服助手 v1.1 · 候选","adapter","MOCK","variant",variant,"capability","instrumented","timeout_seconds",10,"idempotent",true,"input_price",1.0,"output_price",2.0,"price_version","demo-price-2026-10","currency","CNY","secret_ref","");
            config.put("config_hash",hash(config));store.insert("agent","project-demo",config);
        }
    }
}
