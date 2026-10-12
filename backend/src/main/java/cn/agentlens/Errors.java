package cn.agentlens;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import static cn.agentlens.Json.*;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)ResponseEntity<ObjectNode> missing(Exception e){return ResponseEntity.status(404).body(obj("code","NOT_FOUND","message","资源不存在"));}
    @ExceptionHandler(ApiError.class)ResponseEntity<ObjectNode> api(ApiError e){return ResponseEntity.status(e.status).body(obj("code",e.code,"message",e.getMessage(),"details",e.details,"request_id",id("req")));}
    @ExceptionHandler(HttpMessageNotReadableException.class)ResponseEntity<ObjectNode> invalid(Exception e){return ResponseEntity.badRequest().body(obj("code","INVALID_JSON","message","请求 JSON 格式错误"));}
    @ExceptionHandler(Exception.class)ResponseEntity<ObjectNode> other(Exception e){org.slf4j.LoggerFactory.getLogger(Errors.class).error("Unhandled {}",e.getClass().getSimpleName());return ResponseEntity.internalServerError().body(obj("code","INTERNAL_ERROR","message","平台内部错误，请查看服务日志","request_id",id("req")));}
}
