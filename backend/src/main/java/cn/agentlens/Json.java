package cn.agentlens;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

public final class Json {
    public static final ObjectMapper M = new ObjectMapper();
    public static ObjectNode obj(Object... kv) {
        ObjectNode n = M.createObjectNode();
        for (int i=0; i<kv.length; i+=2) n.set(kv[i].toString(), M.valueToTree(kv[i+1]));
        return n;
    }
    public static ArrayNode arr() { return M.createArrayNode(); }
    public static String str(JsonNode n, String k) { return n.path(k).asText(""); }
    public static String now() { return Instant.now().toString(); }
    public static String id(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0,12); }
    public static JsonNode parse(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new ApiError(400,"INVALID_JSON","JSON 格式错误"); }
    }
    public static JsonNode canonical(JsonNode n) {
        if(n.isObject()) { ObjectNode o=obj(); TreeSet<String> keys=new TreeSet<>(); n.fieldNames().forEachRemaining(keys::add); for(String k:keys) o.set(k,canonical(n.get(k))); return o; }
        if(n.isArray()) { ArrayNode a=arr(); n.forEach(v->a.add(canonical(v))); return a; }
        return n;
    }
    public static String hash(JsonNode n) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(n).toString().getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public static String required(JsonNode n,String k) {
        String v=str(n,k).trim(); if(v.isEmpty() || v.length()>10000) throw new ApiError(400,"VALIDATION","缺失或无效字段："+k); return v;
    }
    public static void check(boolean ok,int status,String code,String message) { if(!ok) throw new ApiError(status,code,message); }
    public static String escape(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
}
