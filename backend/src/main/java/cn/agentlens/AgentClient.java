package cn.agentlens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static cn.agentlens.Json.*;

@Component
public class AgentClient {
    private final Set<String> allowed;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public AgentClient(@Value("${agentlens.allowed-origins}") String origins) {
        allowed=new HashSet<>();for(String s:origins.split(","))if(!s.isBlank())allowed.add(s.trim());
    }
    public void validate(JsonNode config) {
        if(str(config,"adapter").equals("MOCK")) return;
        URI uri;
        try {uri=URI.create(required(config,"endpoint"));}catch(IllegalArgumentException e){throw new ApiError(400,"ENDPOINT","接口地址无效");}
        check(Set.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getFragment()==null&&uri.getQuery()==null,400,"ENDPOINT","仅支持无凭据、无查询参数的 HTTP(S) 地址");
        String origin=uri.getScheme()+"://"+uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort());
        check(allowed.contains(origin),400,"ENDPOINT_DENIED","接口来源未加入服务器 AGENT_ALLOWED_ORIGINS 允许列表");
        // Literal IPs prevent DNS rebinding in this minimal adapter. Domain endpoints need an approved egress proxy.
        check(uri.getHost().matches("[0-9.]+"),400,"ENDPOINT_IP_REQUIRED","首版企业接入使用固定 IPv4 或固定出口代理地址");
        check(!uri.getHost().startsWith("169.254.")&&!uri.getHost().equals("0.0.0.0"),400,"ENDPOINT_DENIED","禁止访问链路本地与元数据地址");
        String ref=str(config,"secret_ref");check(ref.isEmpty()||ref.matches("AGENT_SECRET_[A-Z0-9_]+"),400,"SECRET_REF","密钥只允许引用 AGENT_SECRET_ 前缀的环境变量");
    }
    public ObjectNode call(JsonNode config,JsonNode input,String requestId,int port) throws Exception {
        boolean mock=str(config,"adapter").equals("MOCK");
        if(!mock)validate(config);
        String endpoint=mock?"http://127.0.0.1:"+port+"/mock/"+str(config,"variant"):str(config,"endpoint");
        ObjectNode body=obj("request_id",requestId,"case_id",str(input,"case_id"),"input",str(input,"input"),"session_id",requestId,"context",input.path("context"));
        if(mock)body.set("demo_case",input);
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(config.path("timeout_seconds").asInt(60)))
            .header("Content-Type","application/json").header("Idempotency-Key",requestId).POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        String secret=mock?null:System.getenv(str(config,"secret_ref"));
        if(secret!=null&&!secret.isBlank())builder.header("Authorization","Bearer "+secret);
        HttpResponse<java.io.InputStream> response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var stream=response.body()) {
            if(response.statusCode()<200||response.statusCode()>=300)throw new CallFailure("HTTP_"+response.statusCode(),response.statusCode()==429||response.statusCode()>=500);
            byte[] bytes=stream.readNBytes(1_048_577);if(bytes.length>1_048_576)throw new CallFailure("RESPONSE_TOO_LARGE",false);
            JsonNode n=parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            if(!n.isObject()||!n.path("output").isTextual())throw new CallFailure("INVALID_RESPONSE",false);
            ObjectNode out=obj("output",n.path("output"),"usage",n.path("usage"),"trace",n.has("trace")?n.path("trace"):arr(),"trace_complete",n.path("trace_complete").asBoolean(),"source",mock?"SIMULATED":"EXTERNAL");
            return out;
        }
    }
    public static class CallFailure extends Exception {
        public final String code; public final boolean retryable;
        CallFailure(String code,boolean retryable){super(code);this.code=code;this.retryable=retryable;}
    }
}
