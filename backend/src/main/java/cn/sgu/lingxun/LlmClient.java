package cn.sgu.lingxun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面向 OpenAI-compatible /chat/completions 的最小客户端，不绑定具体厂商。
 * API Key 只从运行环境读取，不进入源码、前端或日志。
 */
@Component
class LlmClient {
    record Candidate(String role, String stage, String type, String value, String quote,
                     boolean uncertain, String reason) {}

    static class LlmException extends RuntimeException {
        LlmException(String message) {
            super(message);
        }
    }

    private final ObjectMapper json;
    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final int maxInputChars;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    LlmClient(ObjectMapper json,
              @Value("${lingxun.llm.enabled:false}") boolean enabled,
              @Value("${lingxun.llm.base-url:https://api.deepseek.com}") String baseUrl,
              @Value("${lingxun.llm.api-key:}") String apiKey,
              @Value("${lingxun.llm.model:deepseek-chat}") String model,
              @Value("${lingxun.llm.timeout-seconds:20}") int timeoutSeconds,
              @Value("${lingxun.llm.max-input-chars:12000}") int maxInputChars) {
        this.json = json;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.timeoutSeconds = Math.max(3, timeoutSeconds);
        this.maxInputChars = Math.max(1000, maxInputChars);
    }

    boolean available() {
        return enabled && !apiKey.isBlank() && !baseUrl.isBlank() && !model.isBlank();
    }

    String status() {
        if (!enabled) return "DISABLED";
        if (apiKey.isBlank()) return "NO_KEY";
        if (baseUrl.isBlank() || model.isBlank()) return "CONFIG_INCOMPLETE";
        return "READY";
    }

    String model() {
        return available() ? model : null;
    }

    int maxInputChars() {
        return maxInputChars;
    }

    List<Candidate> extract(String prompt) throws LlmException {
        if (!available()) throw new LlmException("LLM 未启用或未配置 API Key");
        if (prompt != null && prompt.length() > maxInputChars * 3) {
            throw new LlmException("通知正文超过模型输入限制");
        }
        String payload;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            body.put("temperature", 0);
            body.put("max_tokens", 2000);
            body.put("stream", false);
            payload = json.writeValueAsString(body);
        } catch (Exception error) {
            throw new LlmException("模型请求构造失败");
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint())
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
        } catch (IllegalArgumentException invalid) {
            throw new LlmException("模型服务地址配置有误");
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LlmException("模型调用被中断");
        } catch (Exception unavailable) {
            throw new LlmException("模型服务暂时不可用");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new LlmException("模型服务返回 HTTP " + response.statusCode());
        }

        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (Exception invalid) {
            throw new LlmException("模型返回内容不是有效 JSON");
        }
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        return parseCandidates(stripFence(content));
    }

    private URI endpoint() {
        String clean = baseUrl.replaceAll("/+$", "");
        if (!clean.endsWith("/chat/completions")) clean += "/chat/completions";
        return URI.create(clean);
    }

    private List<Candidate> parseCandidates(String content) throws LlmException {
        JsonNode root;
        try {
            root = json.readTree(content);
        } catch (Exception invalid) {
            throw new LlmException("模型没有返回可解析的字段 JSON");
        }
        JsonNode array = root.isArray() ? root : root.path("fields");
        if (!array.isArray()) throw new LlmException("模型字段 JSON 缺少 fields 数组");
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode node : array) {
            String role = text(node, "role");
            String stage = text(node, "stage");
            String type = text(node, "type");
            String value = text(node, "value");
            String quote = text(node, "quote");
            if (role == null || stage == null || type == null || quote == null || quote.isBlank()) continue;
            candidates.add(new Candidate(role.strip(), stage.strip(), type.strip(),
                    value == null || value.isBlank() ? null : value.strip(), quote.strip(),
                    node.path("uncertain").asBoolean(false), text(node, "reason")));
        }
        return candidates;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String stripFence(String content) {
        if (content == null) return "";
        String value = content.strip();
        int first = value.indexOf('{');
        int last = value.lastIndexOf('}');
        if (first >= 0 && last > first) return value.substring(first, last + 1);
        return value;
    }
}
