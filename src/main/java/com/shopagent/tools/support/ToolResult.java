package com.shopagent.tools.support;

/**
 * 工具统一返回结构。
 * Why：W3 交易工具要在 data 前插入幂等/锁校验，统一结构让模型侧零改动；
 * 异常在工具内消化转成失败结果，不把堆栈抛给模型（防重试死循环）。
 */
public record ToolResult(int code, String msg, Object data) {

    public static final int CODE_SUCCESS = 0;
    public static final int CODE_BAD_PARAM = 40001;
    public static final int CODE_NOT_FOUND = 40401;
    public static final int CODE_ERROR = 50001;

    public static ToolResult ok(Object data) {
        return new ToolResult(CODE_SUCCESS, "success", data);
    }

    public static ToolResult badParam(String msg) {
        return new ToolResult(CODE_BAD_PARAM, msg, null);
    }

    public static ToolResult notFound(String msg) {
        return new ToolResult(CODE_NOT_FOUND, msg, null);
    }

    public static ToolResult error(String msg) {
        return new ToolResult(CODE_ERROR, msg, null);
    }
}
