package com.jobai.common;

public enum ErrorCode {
    SUCCESS(200, "success"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用"),

    // 业务异常码
    KNOWLEDGE_NOT_FOUND(1001, "知识库不存在"),
    DOCUMENT_PARSE_FAILED(1002, "文档解析失败"),
    EMBEDDING_FAILED(1003, "向量化失败"),
    RETRIEVAL_FAILED(1004, "检索失败"),
    AGENT_EXECUTION_FAILED(2001, "Agent 执行失败"),
    LLM_SERVICE_FAILED(2002, "LLM 服务调用失败"),
    QUIZ_GENERATE_FAILED(2003, "出题失败"),

    // 认证异常码
    USERNAME_EXISTS(3001, "用户名已存在"),
    BAD_CREDENTIALS(3002, "用户名或密码错误"),
    NOT_AUTHENTICATED(3003, "请先登录");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}
