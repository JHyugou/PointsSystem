package com.pointshome.api.ai;

import com.pointshome.api.common.ApiProblem;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeepSeekGatewayTest {
    @Test
    void readsLiveModelMetadataAndRejectsBadCredentials() throws Exception {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException error) {
            Assumptions.assumeFalse(String.valueOf(error.getMessage())
                            .contains("Unable to establish loopback connection"),
                    "此 Windows 环境需要先设置 jdk.net.unixdomain.tmpdir 才能创建本地测试服务器");
            throw error;
        }
        server.createContext("/models", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            int status = "Bearer sk-valid-test-key".equals(authorization) ? 200 : 401;
            String body = status == 200 ? """
                    {"object":"list","data":[
                      {"id":"deepseek-text","name":"Text","input_modalities":["text"]},
                      {"id":"deepseek-flash","name":"Flash","input_modalities":["text","image"]}
                    ]}
                    """ : "{}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.createContext("/chat/completions", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = "Bearer sk-valid-test-key".equals(authorization)
                    && requestBody.contains("deepseek-flash")
                    && requestBody.contains("reasoning_effort") ? 200 : 400;
            byte[] bytes = (status == 200
                    ? "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}" : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            DeepSeekGateway gateway = new DeepSeekGateway(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals(List.of(
                    new DeepSeekGateway.Model("deepseek-flash", "Flash", true),
                    new DeepSeekGateway.Model("deepseek-text", "Text", false)),
                    gateway.models("sk-valid-test-key"));
            gateway.probe("sk-valid-test-key", "deepseek-flash");
            ApiProblem rejected = assertThrows(ApiProblem.class,
                    () -> gateway.models("sk-invalid-test-key"));
            assertEquals("AI_API_KEY_REJECTED", rejected.code());
        } finally {
            server.stop(0);
        }
    }
}
