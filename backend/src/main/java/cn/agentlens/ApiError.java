package cn.agentlens;

import com.fasterxml.jackson.databind.JsonNode;

public class ApiError extends RuntimeException {
    public final int status;
    public final String code;
    public final JsonNode details;
    public ApiError(int status,String code,String message) { this(status,code,message,Json.arr()); }
    public ApiError(int status,String code,String message,JsonNode details) { super(message);this.status=status;this.code=code;this.details=details; }
}
