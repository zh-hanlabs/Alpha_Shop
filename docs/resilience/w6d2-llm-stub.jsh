// W6D2 记忆冒烟桩（在 w6d1 限流桩基础上的 marker 回路版，零密钥零成本零外呼）。
// Why：记忆连续性的关键是「第 2 轮请求体里是否带着第 1 轮消息」——这由请求体本身可判，
// 无需真 LLM 理解语义。桩从请求体统计：marker-[0-9a-f]+ 出现次数（历史命中）与
// "role":" 出现次数（消息条数），回答 MEMORY-CHECK: marker_hits=N history_msgs=M。
// 断言口径：第 2 轮 recall 时 marker_hits=2 = 记忆在；=1 = 失忆（W1 重启即失忆的对照基线）。
// 用法：jshell -q docs/resilience/w6d2-llm-stub.jsh（配合 DEEPSEEK_BASE_URL=http://127.0.0.1:18081 起应用）
import com.sun.net.httpserver.* ;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

var server = HttpServer.create(new InetSocketAddress(18081), 0);
server.createContext("/", ex -> {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Matcher marker = Pattern.compile("marker-[0-9a-f]+").matcher(body);
    int hits = 0;
    while (marker.find()) { hits++; }
    Matcher role = Pattern.compile("\"role\":\"").matcher(body);
    int roles = 0;
    while (role.find()) { roles++; }
    String reply = "MEMORY-CHECK: marker_hits=" + hits + " history_msgs=" + roles;
    String delta = "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + reply + "\"}}]}";
    byte[] payload = ("data: " + delta + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/event-stream");
    ex.sendResponseHeaders(200, payload.length);
    try (OutputStream os = ex.getResponseBody()) { os.write(payload); }
});
server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));
server.start();
System.out.println("memory-check LLM stub on 18081");
Thread.sleep(1800_000);
