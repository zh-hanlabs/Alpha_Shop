// W6D1 限流冒烟用的本地 LLM 桩（OpenAI 兼容 SSE，零密钥零成本零外呼）。
// Why：DEEPSEEK_API_KEY 只走环境变量不入仓（AGENTS.md 规则 4），限流冒烟只需
// 「过闸请求能拿到 200 回答」，桩模型即可满足；真 LLM 端到端由 W1-W5 冒烟覆盖。
// 用法：jshell -q docs/resilience/w6d1-llm-stub.jsh（配合 DEEPSEEK_BASE_URL=http://127.0.0.1:18081 起应用）
import com.sun.net.httpserver.* ;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

var server = HttpServer.create(new InetSocketAddress(18081), 0);
server.createContext("/", ex -> {
    String delta1 = "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"你好，这是 W6D1 本地桩模型的回答。\"}}]}";
    String delta2 = "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"（桩）\"}}]}";
    String body = "data: " + delta1 + "\n\n"
                + "data: " + delta2 + "\n\n"
                + "data: [DONE]\n\n";
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/event-stream");
    ex.sendResponseHeaders(200, payload.length);
    try (OutputStream os = ex.getResponseBody()) { os.write(payload); }
});
server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));
server.start();
System.out.println("LLM stub listening on 18081");
Thread.sleep(900_000);
