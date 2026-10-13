package com.pointshome.api.points;

import com.jayway.jsonpath.JsonPath;
import com.pointshome.api.auth.VerificationMailSender;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:points-flow;DB_CLOSE_DELAY=-1",
        "app.security.otp-secret=0123456789abcdef0123456789abcdef",
        "app.mail.mode=console"
})
@AutoConfigureMockMvc
@Import(PointFlowTest.MailConfiguration.class)
class PointFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PointSettlement settlement;
    @Autowired TestMailSender mail;

    @Test
    void claimsLimitsPermissionsDelayedDeductionsAndReversals() throws Exception {
        String guardian = register("point-parent@qq.com");
        String outsider = register("other-parent@163.com");
        String childId = value(mvc.perform(auth(post("/api/v1/family/children"), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"point_child\",\"password\":\"child-pass-123\",\"displayName\":\"小明\"}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        String child = value(mvc.perform(post("/api/v1/auth/child-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"point_child\",\"password\":\"child-pass-123\"}"))
                .andExpect(status().isOk()).andReturn(), "$.token");

        mvc.perform(auth(get("/api/v1/points/children/{id}", childId), outsider))
                .andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/points/children/{id}", childId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(0));

        String itemId = value(mvc.perform(auth(post("/api/v1/points/children/{id}/items", childId), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"认真写作业\",\"points\":10,\"maxUses\":2}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        mvc.perform(auth(post("/api/v1/points/children/{id}/items", childId), child)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权\",\"points\":10}"))
                .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/v1/points/items/{id}/grant", itemId), child)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());

        String claim1 = claim(child, itemId);
        mvc.perform(auth(post("/api/v1/points/items/{id}/claims", itemId), child)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        mvc.perform(auth(post("/api/v1/points/claims/{id}/approve", claim1), outsider))
                .andExpect(status().isForbidden());
        String award1 = value(mvc.perform(auth(post("/api/v1/points/claims/{id}/approve", claim1), guardian))
                .andExpect(status().isOk()).andReturn(), "$.id");
        mvc.perform(auth(post("/api/v1/points/claims/{id}/approve", claim1), guardian))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(award1));
        String claim2 = claim(child, itemId);
        mvc.perform(auth(post("/api/v1/points/claims/{id}/approve", claim2), guardian))
                .andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/points/children/{id}", childId), child))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(20))
                .andExpect(jsonPath("$.items[0].remainingUses").value(0));
        mvc.perform(auth(post("/api/v1/points/items/{id}/claims", itemId), child)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        mvc.perform(auth(post("/api/v1/points/actions/{id}/reverse", award1), guardian))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVERSED"));
        mvc.perform(auth(get("/api/v1/points/children/{id}", childId), child))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(10))
                .andExpect(jsonPath("$.items[0].remainingUses").value(1))
                .andExpect(jsonPath("$.ledger.length()").value(3));

        String requestId = UUID.randomUUID().toString();
        String deductionBody = "{\"points\":4,\"reason\":\"晚睡\",\"requestId\":\"" + requestId + "\"}";
        String deduction1 = value(mvc.perform(auth(post("/api/v1/points/children/{id}/deductions", childId), guardian)
                        .contentType(MediaType.APPLICATION_JSON).content(deductionBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn(), "$.id");
        mvc.perform(auth(post("/api/v1/points/children/{id}/deductions", childId), guardian)
                        .contentType(MediaType.APPLICATION_JSON).content(deductionBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(deduction1));
        mvc.perform(auth(post("/api/v1/points/actions/{id}/reverse", deduction1), outsider))
                .andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/points/children/{id}", childId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(10));
        mvc.perform(auth(post("/api/v1/points/actions/{id}/reverse", deduction1), guardian))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        String deduction2 = value(mvc.perform(auth(post("/api/v1/points/children/{id}/deductions", childId), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"points\":3,\"reason\":\"需要重新评估\",\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andReturn(), "$.id");
        jdbc.update("UPDATE point_action SET due_at = ? WHERE id = ?", System.currentTimeMillis() - 1, deduction2);
        settlement.settleDueDeductions();
        mvc.perform(auth(get("/api/v1/points/children/{id}", childId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(7));
        mvc.perform(auth(post("/api/v1/points/actions/{id}/reverse", deduction2), guardian))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVERSED"));
        mvc.perform(auth(get("/api/v1/points/children/{id}", childId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(10));

        String negativeItem = value(mvc.perform(auth(post("/api/v1/points/children/{id}/items", childId), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"忘记整理书桌\",\"points\":-2,\"maxUses\":1}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        mvc.perform(auth(post("/api/v1/points/items/{id}/claims", negativeItem), child)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        String negativeAction = value(mvc.perform(auth(post("/api/v1/points/items/{id}/grant", negativeItem), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn(), "$.id");
        mvc.perform(auth(post("/api/v1/points/actions/{id}/reverse", negativeAction), guardian))
                .andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/points/children/{id}", childId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].remainingUses").value(1));

        String grantRequest = UUID.randomUUID().toString();
        String grantBody = "{\"requestId\":\"" + grantRequest + "\"}";
        String grantId = value(mvc.perform(auth(post("/api/v1/points/items/{id}/grant", itemId), guardian)
                        .contentType(MediaType.APPLICATION_JSON).content(grantBody))
                .andExpect(status().isOk()).andReturn(), "$.id");
        mvc.perform(auth(post("/api/v1/points/items/{id}/grant", itemId), guardian)
                        .contentType(MediaType.APPLICATION_JSON).content(grantBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(grantId));
        mvc.perform(auth(get("/api/v1/points/children/{id}", childId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(20));

        String expiringItem = value(mvc.perform(auth(post("/api/v1/points/children/{id}/items", childId), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"限时任务\",\"points\":5,\"durationDays\":1}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        jdbc.update("UPDATE point_item SET expires_at = ? WHERE id = ?", System.currentTimeMillis() - 1, expiringItem);
        mvc.perform(auth(post("/api/v1/points/items/{id}/claims", expiringItem), child)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        mvc.perform(auth(post("/api/v1/points/items/{id}/grant", expiringItem), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict());
    }

    private String claim(String child, String itemId) throws Exception {
        return value(mvc.perform(auth(post("/api/v1/points/items/{id}/claims", itemId), child)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
    }

    private String register(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/verification-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        return value(mvc.perform(post("/api/v1/auth/guardians")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + mail.code
                                + "\",\"password\":\"guardian-pass-123\",\"displayName\":\"家长\"}"))
                .andExpect(status().isCreated()).andReturn(), "$.token");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String value(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    @TestConfiguration
    static class MailConfiguration {
        @Bean @Primary TestMailSender testMailSender() { return new TestMailSender(); }
    }

    static class TestMailSender implements VerificationMailSender {
        volatile String code;
        @Override public void send(String email, String code, Purpose purpose) { this.code = code; }
    }
}
