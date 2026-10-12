package cn.agentlens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.agentlens.Json.*;

@Service
public class ReportService {
    private final Platform platform;
    public ReportService(Platform p){platform=p;}
    public ObjectNode create(JsonNode request,Authentication auth){
        synchronized(platform){
            String baseline=required(request,"baseline_run_id"),candidate=required(request,"candidate_run_id");
            String mode=request.path("mode").asText("redacted");check(Set.of("redacted","internal").contains(mode),400,"MODE","报告模式无效");
            ObjectNode comparison=platform.comparison(baseline,candidate,auth);String project=comparison.path("baseline").path("project_id").asText();
            String timestamp=now();ArrayNode scoreIds=arr();for(JsonNode row:comparison.path("rows")){scoreIds.add(row.path("baseline").path("score").path("id"));scoreIds.add(row.path("candidate").path("score").path("id"));}
            String html=render(comparison,mode,timestamp);
            ObjectNode report=platform.store.insert("report",project,obj("name","Agent 版本验收报告","baseline_run_id",baseline,"candidate_run_id",candidate,"mode",mode,"snapshot_at",timestamp,"score_ids",scoreIds,"template_version","report-v1","html",html));
            platform.audit(project,auth.getName(),"REPORT_EXPORTED",str(report,"id"));ObjectNode summary=report.deepCopy();summary.remove("html");return summary;
        }
    }
    private String rate(JsonNode n){return n.isNumber()?String.format(Locale.ROOT,"%.1f%%",n.asDouble()*100):"无法计算";}
    private String render(ObjectNode c,String mode,String time){
        boolean redacted=mode.equals("redacted");StringBuilder s=new StringBuilder("<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'\"><title>AgentLens · 版本验收报告</title><style>body{font:15px/1.8 system-ui,sans-serif;color:#18243b;max-width:1080px;margin:48px auto;padding:0 28px}h1{font-size:32px}h2{margin-top:36px}small{color:#667085}.banner{padding:18px;background:#eef3ff;border-left:4px solid #536ee8}.grid{display:flex;gap:18px;flex-wrap:wrap}.metric{background:#f5f7fb;padding:18px;flex:1}.metric strong{display:block;font-size:26px}table{border-collapse:collapse;width:100%;font-size:13px}td,th{border:1px solid #dde3ed;padding:10px;text-align:left;vertical-align:top;overflow-wrap:anywhere}pre{white-space:pre-wrap;background:#f5f7fb;padding:16px;overflow-wrap:anywhere}.good{color:#0a8766}.bad{color:#cb4456}@media print{body{margin:12px}tr{break-inside:avoid}}</style><body>");
        s.append("<small>AGENTLENS / EVALUATION REPORT</small><h1>Agent 版本验收报告</h1><p>生成时间：").append(escape(time)).append(" · ").append(redacted?"脱敏演示模式":"内部完整模式").append("</p>");
        s.append("<div class='banner'>数据来源：基线 ").append(escape(c.path("baseline").path("manifest").path("source").asText())).append(" / 候选 ").append(escape(c.path("candidate").path("manifest").path("source").asText())).append("。SIMULATED 表示自建模拟演示，不能作为真实企业性能或业务收益证据。</div>");
        s.append("<h2>01 · 配对比较</h2><div class='grid'><div class='metric'>基线配对通过率<strong>").append(rate(c.path("baseline_paired_rate"))).append("</strong></div><div class='metric'>候选配对通过率<strong>").append(rate(c.path("candidate_paired_rate"))).append("</strong></div><div class='metric'>共同判定样本<strong>").append(c.path("paired").asInt()).append("</strong></div></div>");
        s.append("<p>改善 ").append(c.path("improved")).append(" · 退化 ").append(c.path("regressed")).append(" · 维持 ").append(c.path("unchanged")).append(" · 不可比 ").append(c.path("incomparable")).append("。配对统计仅包含双方均已判定的相同 case。</p>");
        s.append("<table><tr><th>指标</th><th>基线</th><th>候选</th></tr>");
        for(String[] row:new String[][]{{"total","计划样本数"},{"pass","通过"},{"fail","失败"},{"unknown","未知"},{"pending","待评"},{"score_errors","评分异常"},{"call_errors","执行异常"},{"cancelled","取消"},{"attempt_count","实际请求次数"}})s.append("<tr><td>").append(row[1]).append("</td><td>").append(c.path("baseline_metrics").path(row[0])).append("</td><td>").append(c.path("candidate_metrics").path(row[0])).append("</td></tr>");
        for(String[] row:new String[][]{{"pass_rate","已判定通过率"},{"coverage","判定覆盖率"},{"all_pass_rate","全样本通过占比"},{"cost_coverage","成本数据覆盖率"}})s.append("<tr><td>").append(row[1]).append("</td><td>").append(rate(c.path("baseline_metrics").path(row[0]))).append("</td><td>").append(rate(c.path("candidate_metrics").path(row[0]))).append("</td></tr>");
        for(String[] row:new String[][]{{"p50_ms","成功请求 P50 / ms"},{"p95_ms","成功请求 P95 / ms"},{"latency_samples","成功请求耗时样本数"},{"known_cost","已知成本 / CNY"}})s.append("<tr><td>").append(row[1]).append("</td><td>").append(value(c.path("baseline_metrics").path(row[0]))).append("</td><td>").append(value(c.path("candidate_metrics").path(row[0]))).append("</td></tr>");
        s.append("</table><h2>02 · 案例与复核依据</h2><table><tr><th>案例</th><th>变化</th><th>基线</th><th>候选</th></tr>");
        int index=0;for(JsonNode row:c.path("rows")){
            s.append("<tr><td>").append(redacted?"案例 "+(++index):escape(str(row,"case_id"))+"<br>"+escape(str(row,"input"))).append("</td><td>").append(escape(str(row,"change"))).append("</td>");
            for(String side:List.of("baseline","candidate")) {JsonNode r=row.path(side);s.append("<td>").append(escape(str(r,"status"))).append(" / ").append(escape(str(r.path("score"),"state"))).append("<br>");if(!redacted)s.append(escape(str(r,"output"))).append("<br><small>").append(escape(str(r.path("score"),"reason"))).append("</small>");else s.append("问题、回答、理由与引用已隐藏");s.append("</td>");}
            s.append("</tr>");
        }
        s.append("</table><h2>03 · 版本与适用限制</h2>");
        for(String side:List.of("baseline","candidate")) {JsonNode r=c.path(side);ObjectNode manifest=((ObjectNode)r.path("manifest")).deepCopy();if(redacted)manifest.remove("agent_name");s.append("<h3>").append(side.equals("baseline")?"基线":"候选").append("</h3><p>批次：").append(escape(str(r,"id"))).append(" · manifest hash：").append(escape(str(r,"manifest_hash"))).append("</p><pre>").append(escape(manifest.toPrettyString())).append("</pre>");}
        s.append("<p>评分：确定性业务规则和人工复核；模型裁判未启用。无完整 trace 不判定工具正确性，无证据不宣称忠实度。成本缺失不按零计算，已知费用覆盖所有可计价尝试。规则通过率不能替代生产可用性结论。</p><p>修正建议：逐项复核退化与无法判断案例，明确业务依据后修改 Agent，使用相同问题集及规则新建批次复测。本报告固定导出时的评分记录，后续改判不改变该文件。</p><footer><small>AgentLens v0.1 · 本文件可离线打开，不包含外部脚本与资源。</small></footer></body></html>");return s.toString();
    }
    private String value(JsonNode n){return n.isNull()||n.isMissingNode()?"未知":escape(n.asText());}
}
