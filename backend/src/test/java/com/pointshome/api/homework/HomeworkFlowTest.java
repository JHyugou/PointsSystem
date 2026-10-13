package com.pointshome.api.homework;

import com.jayway.jsonpath.JsonPath;
import com.pointshome.api.ai.DeepSeekGateway;
import com.pointshome.api.auth.VerificationMailSender;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:homework-flow;DB_CLOSE_DELAY=-1",
        "app.security.otp-secret=0123456789abcdef0123456789abcdef",
        "app.ai.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.homework.storage-dir=./target/homework-flow-test",
        "app.mail.mode=console"
})
@AutoConfigureMockMvc
@Import(HomeworkFlowTest.MailConfiguration.class)
class HomeworkFlowTest {
    @Autowired MockMvc mvc;
    @Autowired TestMailSender mail;
    @MockitoBean DeepSeekGateway deepSeek;
    @MockitoBean HomeworkAiGateway ai;

    @Test
    void parentReviewsPhotosBeforeAnyPointsChangeAndOtherAccountsCannotReadThem() throws Exception {
        String parent = register("homework-parent@qq.com");
        MvcResult childResult = mvc.perform(post("/api/v1/family/children")
                        .header("Authorization", bearer(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"homework_child\",\"password\":\"child-pass-123\",\"displayName\":\"孩子\"}"))
                .andExpect(status().isCreated()).andReturn();
        String childId = JsonPath.read(childResult.getResponse().getContentAsString(), "$.id");
        MvcResult childLogin = mvc.perform(post("/api/v1/auth/child-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"homework_child\",\"password\":\"child-pass-123\"}"))
                .andExpect(status().isOk()).andReturn();
        String childToken = JsonPath.read(childLogin.getResponse().getContentAsString(), "$.token");

        MvcResult itemResult = mvc.perform(post("/api/v1/points/children/{id}/items", childId)
                        .header("Authorization", bearer(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"作业认真\",\"points\":3}"))
                .andExpect(status().isCreated()).andReturn();
        String itemId = JsonPath.read(itemResult.getResponse().getContentAsString(), "$.id");

        when(deepSeek.models("sk-homework-test-key"))
                .thenReturn(List.of(new DeepSeekGateway.Model("deepseek-flash", "Flash", true)));
        mvc.perform(put("/api/v1/ai/deepseek")
                        .header("Authorization", bearer(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-homework-test-key\",\"modelId\":\"deepseek-flash\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/homework/children/{id}/rule", childId)
                        .header("Authorization", bearer(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodItemId\":\"" + itemId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goodItemId").value(itemId));

        MockMultipartFile photo = new MockMultipartFile("photos", "page.jpg", "image/jpeg", jpeg());
        mvc.perform(multipart("/api/v1/homework/children/{id}/submissions", childId)
                        .file(photo).param("subject", "数学")
                        .header("Authorization", bearer(childToken)))
                .andExpect(status().isForbidden());
        MvcResult uploaded = mvc.perform(multipart("/api/v1/homework/children/{id}/submissions", childId)
                        .file(photo).param("subject", "数学")
                        .header("Authorization", bearer(parent)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn();
        String submissionId = JsonPath.read(uploaded.getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/v1/points/children/{id}", childId)
                        .header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.balance").value(0));
        mvc.perform(get("/api/v1/homework/submissions/{id}/photos/0", submissionId)
                        .header("Authorization", bearer(parent)))
                .andExpect(status().isOk());

        String outsider = register("homework-other@163.com");
        mvc.perform(get("/api/v1/homework/submissions/{id}/photos/0", submissionId)
                        .header("Authorization", bearer(outsider)))
                .andExpect(status().isNotFound());

        var advice = new HomeworkAiGateway.Review("计算基本正确",
                List.of("过程清楚"),
                List.of(new HomeworkAiGateway.Problem("第 2 题", "漏写单位", "补写单位")),
                List.of("照片右下角有些模糊"), List.of("检查应用题单位"));
        when(ai.review(eq("sk-homework-test-key"), eq("deepseek-flash"), eq("数学"),
                anyString(), anyList())).thenReturn(advice);
        mvc.perform(post("/api/v1/homework/submissions/{id}/analyze", submissionId)
                        .header("Authorization", bearer(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.summary").value("计算基本正确"))
                .andExpect(jsonPath("$.analysis.score").doesNotExist())
                .andExpect(jsonPath("$.analysis.problems[0].location").value("第 2 题"));
        mvc.perform(get("/api/v1/points/children/{id}", childId)
                        .header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.balance").value(0));

        when(ai.followUp(eq("sk-homework-test-key"), eq("deepseek-flash"), eq("数学"),
                eq(advice), anyList(), anyList(), eq("第二题怎么讲？")))
                .thenReturn("先让孩子说出单位。 ");
        mvc.perform(post("/api/v1/homework/submissions/{id}/chat", submissionId)
                        .header("Authorization", bearer(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"第二题怎么讲？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chat.length()").value(2));

        mvc.perform(post("/api/v1/homework/submissions/{id}/review", submissionId)
                        .header("Authorization", bearer(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"EXCELLENT\",\"pointItemId\":\"" + itemId + "\"}"))
                .andExpect(status().isBadRequest());
        String reviewBody = "{\"rating\":\"GOOD\",\"note\":\"家长已核对\",\"pointItemId\":\"" + itemId + "\"}";
        mvc.perform(post("/api/v1/homework/submissions/{id}/review", submissionId)
                        .header("Authorization", bearer(parent))
                        .contentType(MediaType.APPLICATION_JSON).content(reviewBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"))
                .andExpect(jsonPath("$.parentRating").value("GOOD"));
        mvc.perform(post("/api/v1/homework/submissions/{id}/review", submissionId)
                        .header("Authorization", bearer(parent))
                        .contentType(MediaType.APPLICATION_JSON).content(reviewBody))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/points/children/{id}", childId)
                        .header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.balance").value(3));

        mvc.perform(delete("/api/v1/homework/submissions/{id}", submissionId)
                        .header("Authorization", bearer(parent)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/homework/submissions/{id}/photos/0", submissionId)
                        .header("Authorization", bearer(parent)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/points/children/{id}", childId)
                        .header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.balance").value(3));
    }

    private byte[] jpeg() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "jpg", output);
        return output.toByteArray();
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
