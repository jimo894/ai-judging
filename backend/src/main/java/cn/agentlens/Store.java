package cn.agentlens;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static cn.agentlens.Json.*;

/** Immutable payload snapshots and mutable execution envelopes share a small persistent store.
 * Single application/worker deployment; multi-instance claiming is intentionally unsupported. */
@Repository
public class Store {
    private final JdbcTemplate jdbc;
    public Store(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<ObjectNode> find(String id) {
        return jdbc.query("SELECT body FROM documents WHERE id=?",(rs,i)->(ObjectNode)parse(rs.getString(1)),id).stream().findFirst();
    }
    public ObjectNode get(String id,String kind) {
        ObjectNode n=find(id).orElseThrow(()->new ApiError(404,"NOT_FOUND","记录不存在"));
        check(str(n,"kind").equals(kind),404,"NOT_FOUND","记录不存在"); return n;
    }
    public List<ObjectNode> list(String kind,String project) {
        return jdbc.query("SELECT body FROM documents WHERE kind=? AND project_id=? ORDER BY created_at,id",(rs,i)->(ObjectNode)parse(rs.getString(1)),kind,project);
    }
    public List<ObjectNode> all(String kind) {
        return jdbc.query("SELECT body FROM documents WHERE kind=? ORDER BY created_at,id",(rs,i)->(ObjectNode)parse(rs.getString(1)),kind);
    }
    public ObjectNode insert(String kind,String project,ObjectNode n) {
        if(!n.has("id")) n.put("id",id(kind));
        n.put("kind",kind).put("project_id",project);
        if(!n.has("created_at")) n.put("created_at",now());
        jdbc.update("INSERT INTO documents(id,kind,project_id,body,created_at) VALUES(?,?,?,?,?)",str(n,"id"),kind,project,n.toString(),str(n,"created_at"));return n;
    }
    public void save(ObjectNode n) { jdbc.update("UPDATE documents SET body=? WHERE id=?",n.toString(),str(n,"id")); }
}
