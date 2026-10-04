// W6D4 观测冒烟桩 v3：在 w6d2 marker 回路桩基础上增加 tool_call 时序（零密钥零外呼）。
// 用途：端到端验证工具计数埋点——触发词轮返回 OpenAI 流式 tool_call（searchProduct），
// Spring AI 执行真实工具（H2 商品库）后带 role:tool 回调，桩回 TOOL-DONE 收尾。
// 判定顺序：请求体含 "role":"tool" → TOOL-DONE；含 TOOLCALL-TRIGGER → tool_call 三连 chunk；
// 其余 → MEMORY-CHECK（marker 回路，w6d2 同款）。
// 用法：jshell -q docs/resilience/w6d4-llm-stub-toolcall.jsh（DEEPSEEK_BASE_URL=http://127.0.0.1:18081）
import com.sun.net.httpserver.* ;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

var server = HttpServer.create(new InetSocketAddress(18081), 0);
server.createContext("/", ex -> {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String content;
    if (body.contains("\"role\":\"tool\"")) {
        content = "TOOL-DONE: tool result merged into context.";
    } else if (body.contains("TOOLCALL-TRIGGER")) {
        String tc1 = "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"searchProduct\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}";
        String tc2 = "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"keyword\\\":\\\"灯\\\"}\"}}]},\"finish_reason\":null}]}";
        String tc3 = "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}";
        byte[] payload = ("data: " + tc1 + "\n\ndata: " + tc2 + "\n\ndata: " + tc3 + "\n\ndata: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, payload.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(payload); }
        return;
    } else {
        Matcher marker = Pattern.compile("marker-[0-9a-f]+").matcher(body);
        int hits = 0;
        while (marker.find()) { hits++; }
        Matcher role = Pattern.compile("\"role\":\"").matcher(body);
        int roles = 0;
        while (role.find()) { roles++; }
        content = "MEMORY-CHECK: marker_hits=" + hits + " history_msgs=" + roles;
    }
    String delta = "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}";
    byte[] payload = ("data: " + delta + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/event-stream");
    ex.sendResponseHeaders(200, payload.length);
    try (OutputStream os = ex.getResponseBody()) { os.write(payload); }
});
server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));
server.start();
System.out.println("obs smoke LLM stub (tool-call capable) on 18081");
Thread.sleep(1800_000);
