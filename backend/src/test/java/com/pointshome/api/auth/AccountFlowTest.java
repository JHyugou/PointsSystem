package com.pointshome.api.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:account-flow;DB_CLOSE_DELAY=-1",
        "app.security.otp-secret=0123456789abcdef0123456789abcdef",
        "app.mail.mode=console"
})
@AutoConfigureMockMvc
@Import(AccountFlowTest.MailConfiguration.class)
class AccountFlowTest {
    @Autowired MockMvc mvc;
    @Autowired TestMailSender mail;

    @Test
    void guardiansAndChildCanRegisterLoginAndLinkWithRoleChecks() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());

        String firstGuardian = registerGuardian("parent1@qq.com", "第一位家长");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(firstGuardian)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("GUARDIAN"));

        MvcResult created = mvc.perform(post("/api/v1/family/children")
                        .header("Authorization", bearer(firstGuardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"little_star\",\"password\":\"child-pass-123\",\"displayName\":\"小星\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("little_star"))
                .andReturn();
        String childId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/family/children")
                        .header("Authorization", bearer(firstGuardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"second_star\",\"password\":\"child-pass-456\",\"displayName\":\"小月\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/family/children").header("Authorization", bearer(firstGuardian)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        MvcResult childLogin = mvc.perform(post("/api/v1/auth/child-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"little_star\",\"password\":\"child-pass-123\"}"))
                .andExpect(status().isOk()).andReturn();
        String childToken = JsonPath.read(childLogin.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/api/v1/family/children")
                        .header("Authorization", bearer(childToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"forbidden_child\",\"password\":\"child-pass-123\",\"displayName\":\"不允许\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/family/guardians").header("Authorization", bearer(childToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        String secondGuardian = registerGuardian("parent2@163.com", "第二位家长");
        mvc.perform(post("/api/v1/family/children/{id}/password-reset", childId)
                        .header("Authorization", bearer(secondGuardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"new-child-pass-123\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/family/children/{id}/invites", childId)
                        .header("Authorization", bearer(secondGuardian)))
                .andExpect(status().isNotFound());

        MvcResult issued = mvc.perform(post("/api/v1/family/children/{id}/invites", childId)
                        .header("Authorization", bearer(firstGuardian)))
                .andExpect(status().isOk()).andReturn();
        String invite = JsonPath.read(issued.getResponse().getContentAsString(), "$.code");
        mvc.perform(post("/api/v1/family/invites/redeem")
                        .header("Authorization", bearer(secondGuardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + invite + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(childId));
        mvc.perform(post("/api/v1/family/invites/redeem")
                        .header("Authorization", bearer(secondGuardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + invite + "\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/family/children").header("Authorization", bearer(secondGuardian)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/family/guardians").header("Authorization", bearer(childToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        mvc.perform(post("/api/v1/family/children/{id}/password-reset", childId)
                        .header("Authorization", bearer(secondGuardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"new-child-pass-123\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(childToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/child-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"little_star\",\"password\":\"child-pass-123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/child-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"little_star\",\"password\":\"new-child-pass-123\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(firstGuardian)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(firstGuardian)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guardianCanResetPasswordByEmailAndOldSessionsAreRevoked() throws Exception {
        String email = "reset-parent@qq.com";
        String oldToken = registerGuardian(email, "找回密码家长");

        int before = mail.sentCount;
        mvc.perform(post("/api/v1/auth/password-reset-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown-reset@qq.com\"}"))
                .andExpect(status().isAccepted());
        assertEquals(before, mail.sentCount);

        mvc.perform(post("/api/v1/auth/password-reset-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        assertEquals(VerificationMailSender.Purpose.PASSWORD_RESET, mail.lastPurpose);
        String code = mail.lastCode;
        int sent = mail.sentCount;
        mvc.perform(post("/api/v1/auth/password-reset-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        assertEquals(sent, mail.sentCount);

        String wrong = code.equals("000000") ? "000001" : "000000";
        mvc.perform(post("/api/v1/auth/password-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + wrong
                                + "\",\"newPassword\":\"new-guardian-pass-123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(oldToken)))
                .andExpect(status().isOk());

        String resetBody = "{\"email\":\"" + email + "\",\"code\":\"" + code
                + "\",\"newPassword\":\"new-guardian-pass-123\"}";
        mvc.perform(post("/api/v1/auth/password-resets")
                        .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(oldToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/guardian-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"guardian-pass-123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/guardian-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"new-guardian-pass-123\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/password-resets")
                        .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
    }

    @Test
    void failedMailDoesNotReserveACodeAndResetStopsAfterFiveWrongAttempts() throws Exception {
        String email = "retry-mail@qq.com";
        mail.failNext = true;
        mvc.perform(post("/api/v1/auth/verification-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/v1/auth/verification-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/auth/guardians")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + mail.lastCode
                                + "\",\"password\":\"guardian-pass-123\",\"displayName\":\"邮件测试\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/auth/password-reset-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        String code = mail.lastCode;
        String wrong = code.equals("000000") ? "000001" : "000000";
        String prefix = "{\"email\":\"" + email + "\",\"newPassword\":\"replacement-pass-123\",\"code\":\"";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/password-resets")
                            .contentType(MediaType.APPLICATION_JSON).content(prefix + wrong + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/auth/password-resets")
                        .contentType(MediaType.APPLICATION_JSON).content(prefix + code + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/guardian-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"guardian-pass-123\"}"))
                .andExpect(status().isOk());
    }

    private String registerGuardian(String email, String displayName) throws Exception {
        mvc.perform(post("/api/v1/auth/verification-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        String code = mail.lastCode;
        String wrongCode = code.equals("000000") ? "000001" : "000000";
        mvc.perform(post("/api/v1/auth/guardians")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + wrongCode + "\",\"password\":\"guardian-pass-123\",\"displayName\":\"" + displayName + "\"}"))
                .andExpect(status().isBadRequest());
        MvcResult registered = mvc.perform(post("/api/v1/auth/guardians")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"password\":\"guardian-pass-123\",\"displayName\":\"" + displayName + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(registered.getResponse().getContentAsString(), "$.token");
    }

    private static String bearer(String token) { return "Bearer " + token; }

    @TestConfiguration
    static class MailConfiguration {
        @Bean
        @Primary
        TestMailSender testMailSender() { return new TestMailSender(); }
    }

    static class TestMailSender implements VerificationMailSender {
        volatile String lastCode;
        volatile Purpose lastPurpose;
        volatile int sentCount;
        volatile boolean failNext;
        @Override public void send(String email, String code, Purpose purpose) {
            if (failNext) {
                failNext = false;
                throw new MailSendException("simulated mail failure");
            }
            lastCode = code;
            lastPurpose = purpose;
            sentCount++;
        }
    }
}
