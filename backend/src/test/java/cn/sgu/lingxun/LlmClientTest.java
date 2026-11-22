package cn.sgu.lingxun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用本机 mock 服务验证 OpenAI-compatible 请求/响应协议，不代表真实厂商模型调用。
 * 真实调用需要运行环境注入 Key，见 LlmClient 的环境变量配置。
 */
class LlmClientTest {
    @Test
    void parsesOpenAiCompatibleChatCompletionAndServerSideQuote() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"model\":\"mock-model\"", "\"temperature\":0");
            String content = "```json\n"
                    + "{\"fields\":[{\"role\":\"学生\",\"stage\":\"竞赛报名\",\"type\":\"截止\","
                    + "\"value\":\"2026-11-10\",\"quote\":\"报名截止2026年11月10日。\",\"uncertain\":false,\"reason\":\"\"}]}\n"
                    + "```";
            String response = "{\"choices\":[{\"message\":{\"content\":"
                    + new ObjectMapper().writeValueAsString(content) + "}}]}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            LlmClient client = new LlmClient(new ObjectMapper(), true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "mock-key-not-real",
                    "mock-model", 5, 12000);
            List<LlmClient.Candidate> candidates = client.extract("只输出 JSON");
            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).type()).isEqualTo("截止");
            assertThat(candidates.get(0).value()).isEqualTo("2026-11-10");
            assertThat(candidates.get(0).quote()).isEqualTo("报名截止2026年11月10日。");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsHttpFailureWithoutLeakingTheKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            LlmClient client = new LlmClient(new ObjectMapper(), true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "mock-key-not-real",
                    "mock-model", 5, 12000);
            assertThatThrownBy(() -> client.extract("test"))
                    .isInstanceOf(LlmClient.LlmException.class)
                    .hasMessageContaining("HTTP 401")
                    .hasMessageNotContaining("mock-key-not-real");
        } finally {
            server.stop(0);
        }
    }
}
