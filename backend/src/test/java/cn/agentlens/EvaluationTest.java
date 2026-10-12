package cn.agentlens;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.agentlens.Json.*;
import static org.assertj.core.api.Assertions.*;

class EvaluationTest {
 @Test void unknownAndErrorsNeverCountAsBusinessFailures(){
  var pass=obj("status","SUCCEEDED","score",Evaluation.verdict("SCORED",true,"ok"),"attempts",arr().add(obj("status","SUCCEEDED","duration_ms",100,"cost",0.0)));
  var fail=obj("status","SUCCEEDED","score",Evaluation.verdict("SCORED",false,"bad"),"attempts",arr().add(obj("status","SUCCEEDED","duration_ms",200)));
  var unknown=obj("status","CALL_ERROR","score",Evaluation.verdict("UNKNOWN",null,"error"),"attempts",arr().add(obj("status","CALL_ERROR","duration_ms",300)));
  var m=Evaluation.metrics(List.of(pass,fail,unknown));
  assertThat(m.path("pass_rate").asDouble()).isEqualTo(.5);assertThat(m.path("coverage").asDouble()).isEqualTo(2.0/3);
  assertThat(m.path("known_cost").asDouble()).isZero();assertThat(m.path("cost_coverage").asDouble()).isEqualTo(1.0/3);assertThat(m.path("p95_ms").asDouble()).isEqualTo(200);
  assertThat(Evaluation.metrics(List.of()).path("pass_rate").isNull()).isTrue();
 }
 @Test void toolCorrectnessRequiresCompleteTrace(){
  var c=obj("checks",obj("contains",arr().add("已支付"),"tool","query_order","tool_args",obj("order_id","1")));
  assertThat(str(Evaluation.score(c,obj("output","已支付")),"state")).isEqualTo("UNKNOWN");
  var response=obj("output","已支付","trace_complete",true,"trace",arr().add(obj("type","tool","name","query_order","args",obj("order_id","2"))));
  assertThat(Evaluation.score(c,response).path("passed").asBoolean()).isFalse();
 }
 @Test void hashIgnoresObjectFieldOrder(){assertThat(hash(parse("{\"a\":1,\"b\":2}"))).isEqualTo(hash(parse("{\"b\":2,\"a\":1}")));}
}
