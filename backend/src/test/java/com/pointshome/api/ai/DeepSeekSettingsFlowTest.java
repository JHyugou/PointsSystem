package com.pointshome.api.ai;

import com.jayway.jsonpath.JsonPath;
import com.pointshome.api.auth.VerificationMailSender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-flow;DB_CLOSE_DELAY=-1",
        "app.security.otp-secret=0123456789abcdef0123456789abcdef",
        "app.ai.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.mail.mode=console"
})
@AutoConfigureMockMvc
@Import(DeepSeekSettingsFlowTest.MailConfiguration.class)
class DeepSeekSettingsFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestMailSender mail;
    @MockitoBean DeepSeekGateway gateway;

    @Test
    void guardianCanRefreshChooseAndTestWithoutExposingKeyToChild() throws Exception {
        String guardian = register("ai-parent@qq.com");
        String child = createAndLoginChild(guardian);
        String apiKey = "sk-example-secret-123";
        when(gateway.models(apiKey)).thenReturn(List.of(
                new DeepSeekGateway.Model("deepseek-flash", "DeepSeek Flash", true),
                new DeepSeekGateway.Model("deepseek-text", "DeepSeek Text", false)));

        mvc.perform(get("/api/v1/ai/deepseek").header("Authorization", bearer(child)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ai/deepseek/models/refresh")
                        .header("Authorization", bearer(child))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + apiKey + "\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/ai/deepseek/models/refresh")
                        .header("Authorization", bearer(guardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + apiKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].acceptsImages").value(true));

        mvc.perform(put("/api/v1/ai/deepseek")
                        .header("Authorization", bearer(guardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + apiKey + "\",\"modelId\":\"deepseek-text\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_MODEL_NO_IMAGES"));

        mvc.perform(put("/api/v1/ai/deepseek")
                        .header("Authorization", bearer(guardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + apiKey + "\",\"modelId\":\"deepseek-flash\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.modelId").value("deepseek-flash"))
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        String encrypted = jdbc.queryForObject(
                "SELECT encrypted_api_key FROM ai_provider_setting", String.class);
        assertNotEquals(apiKey, encrypted);

        mvc.perform(get("/api/v1/ai/deepseek").header("Authorization", bearer(guardian)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        mvc.perform(post("/api/v1/ai/deepseek/test").header("Authorization", bearer(guardian)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        verify(gateway).probe(apiKey, "deepseek-flash");

        String secondGuardian = register("ai-parent2@163.com");
        mvc.perform(get("/api/v1/ai/deepseek").header("Authorization", bearer(secondGuardian)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    private String register(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/verification-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        MvcResult result = mvc.perform(post("/api/v1/auth/guardians")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + mail.lastCode
                                + "\",\"password\":\"guardian-pass-123\",\"displayName\":\"家长\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private String createAndLoginChild(String guardian) throws Exception {
        mvc.perform(post("/api/v1/family/children")
                        .header("Authorization", bearer(guardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ai_child\",\"password\":\"child-pass-123\",\"displayName\":\"孩子\"}"))
                .andExpect(status().isCreated());
        MvcResult result = mvc.perform(post("/api/v1/auth/child-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ai_child\",\"password\":\"child-pass-123\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private static String bearer(String token) { return "Bearer " + token; }

    @TestConfiguration
    static class MailConfiguration {
        @Bean @Primary TestMailSender testMailSender() { return new TestMailSender(); }
    }

    static class TestMailSender implements VerificationMailSender {
        volatile String lastCode;
        @Override public void send(String email, String code, Purpose purpose) { lastCode = code; }
    }
}
