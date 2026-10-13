package com.pointshome.api.homework;

import com.pointshome.api.ai.DeepSeekGateway;
import com.pointshome.api.common.ApiProblem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HomeworkAiGatewayTest {
    @Test
    void sendsImagesAsDataUrlsAndPreservesUncertaintyWithoutScoring() {
        ObjectMapper json = new ObjectMapper();
        DeepSeekGateway deepSeek = mock(DeepSeekGateway.class);
        HomeworkAiGateway gateway = new HomeworkAiGateway(deepSeek, json);
        String answer = """
                {"summary":"右侧字迹不清，暂不能判断",
                 "strengths":["有列出步骤"],"problems":[],
                 "uncertainties":["第 3 题看不清"],"nextSteps":["重拍第 3 题"]}
                """;
        var response = json.readTree("{\"choices\":[{\"message\":{\"content\":"
                + json.writeValueAsString(answer) + "}}]}");
        when(deepSeek.chat(eq("test-key"), anyMap(), eq(110_000))).thenReturn(response);

        HomeworkAiGateway.Review result = gateway.review("test-key", "deepseek-flash",
                "数学", "没有标准答案", List.of(new HomeworkImageStore.Image(new byte[]{1, 2}, "image/jpeg")));
        assertEquals("右侧字迹不清，暂不能判断", result.summary());
        assertTrue(!json.writeValueAsString(result).contains("score"));
        assertEquals(List.of("第 3 题看不清"), result.uncertainties());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(deepSeek).chat(eq("test-key"), request.capture(), eq(110_000));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.getValue().get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(1).get("content");
        @SuppressWarnings("unchecked")
        Map<String, String> imageUrl = (Map<String, String>) content.get(1).get("image_url");
        assertTrue(imageUrl.get("url").startsWith("data:image/jpeg;base64,"));
    }

    @Test
    void rejectsAProviderResponseWithoutAnExplanation() {
        ObjectMapper json = new ObjectMapper();
        DeepSeekGateway deepSeek = mock(DeepSeekGateway.class);
        HomeworkAiGateway gateway = new HomeworkAiGateway(deepSeek, json);
        var response = json.readTree("{\"choices\":[{\"message\":{\"content\":\"{\\\"problems\\\":[] }\"}}]}");
        when(deepSeek.chat(eq("test-key"), anyMap(), eq(110_000))).thenReturn(response);
        ApiProblem error = assertThrows(ApiProblem.class, () -> gateway.review("test-key",
                "deepseek-flash", "数学", "", List.of(new HomeworkImageStore.Image(new byte[]{1}, "image/jpeg"))));
        assertEquals("HOMEWORK_AI_INVALID", error.code());
    }

    @Test
    void readsOlderAdviceWithoutShowingItsNumericScore() {
        ObjectMapper json = new ObjectMapper();
        String old = """
                {"score":95,"confidence":"HIGH","summary":"注意单位",
                 "strengths":[],"problems":[],"uncertainties":[],"nextSteps":[]}
                """;
        HomeworkAiGateway.Review advice = json.readValue(old, HomeworkAiGateway.Review.class);
        assertEquals("注意单位", advice.summary());
        assertTrue(!json.writeValueAsString(advice).contains("score"));
    }
}
