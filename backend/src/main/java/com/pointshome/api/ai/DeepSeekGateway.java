package com.pointshome.api.ai;

import com.pointshome.api.common.ApiProblem;
import java.io.IOException;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class DeepSeekGateway {
    public record Model(String id, String name, boolean acceptsImages) {}

    private final ObjectMapper json;
    private final URI base;

    public DeepSeekGateway(ObjectMapper json,
                           @Value("${app.ai.deepseek-base-url:https://api.deepseek.com}") String baseUrl) {
        this.json = json;
        this.base = URI.create(baseUrl.replaceAll("/+$", "") + "/");
    }

    public List<Model> models(String apiKey) {
        JsonNode root = call("models", apiKey, null, 18_000);
        JsonNode data = root.path("data");
        if (!data.isArray()) throw invalidResponse();
        List<Model> result = new ArrayList<>();
        for (JsonNode model : data) {
            String id = model.path("id").asText("");
            if (id.isBlank()) continue;
            String name = model.path("name").asText(id);
            boolean images = false;
            JsonNode modalities = model.path("input_modalities");
            if (modalities.isArray()) {
                for (JsonNode modality : modalities) {
                    if ("image".equals(modality.asText())) images = true;
                }
            }
            result.add(new Model(id, name, images));
        }
        result.sort(Comparator.comparing(Model::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public void probe(String apiKey, String modelId) {
        String body = json.writeValueAsString(Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "只回复 OK")),
                "reasoning_effort", "none",
                "max_tokens", 32,
                "stream", false));
        JsonNode response = call("chat/completions", apiKey, body, 50_000);
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()
                || choices.get(0).path("message").path("content").asText("").isBlank()) {
            throw invalidResponse();
        }
    }

    public JsonNode chat(String apiKey, Map<String, Object> body, int timeoutMillis) {
        return call("chat/completions", apiKey, json.writeValueAsString(body), timeoutMillis);
    }

    private JsonNode call(String path, String apiKey, String body, int timeoutMillis) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) base.resolve(path).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6_000);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestMethod(body == null ? "GET" : "POST");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "AI_API_KEY_REJECTED", "DeepSeek 拒绝了 API 密钥，请检查密钥");
            }
            if (status == 402) {
                throw new ApiProblem(HttpStatus.BAD_GATEWAY, "AI_BALANCE_REQUIRED", "DeepSeek 账户余额不足");
            }
            if (status == 429) {
                throw new ApiProblem(HttpStatus.BAD_GATEWAY, "AI_RATE_LIMITED", "DeepSeek 请求过于频繁，请稍后重试");
            }
            if (status < 200 || status >= 300) {
                throw new ApiProblem(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_FAILED",
                        "DeepSeek 暂时无法完成请求（HTTP " + status + "）");
            }
            try {
                try (var input = connection.getInputStream()) {
                    return json.readTree(input);
                }
            } catch (Exception ignored) {
                throw invalidResponse();
            }
        } catch (IOException ignored) {
            throw new ApiProblem(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_UNREACHABLE", "无法连接 DeepSeek，请检查服务器网络");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private ApiProblem invalidResponse() {
        return new ApiProblem(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE", "DeepSeek 返回了无法识别的数据");
    }
}
