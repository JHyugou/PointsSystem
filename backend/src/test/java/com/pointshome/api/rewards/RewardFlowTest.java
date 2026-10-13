package com.pointshome.api.rewards;

import com.jayway.jsonpath.JsonPath;
import com.pointshome.api.auth.VerificationMailSender;
import com.pointshome.api.auth.Account;
import com.pointshome.api.auth.CurrentUser;
import com.pointshome.api.common.ApiProblem;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        "spring.datasource.url=jdbc:h2:mem:rewards-flow;DB_CLOSE_DELAY=-1",
        "app.security.otp-secret=0123456789abcdef0123456789abcdef",
        "app.mail.mode=console"
})
@AutoConfigureMockMvc
@Import(RewardFlowTest.MailConfiguration.class)
class RewardFlowTest {
    @Autowired MockMvc mvc;
    @Autowired TestMailSender mail;
    @Autowired JdbcTemplate jdbc;
    @Autowired RewardService rewards;

    @Test
    void timeRewardsNeedApprovalAndBoundDeviceAndAreDeliveredOnce() throws Exception {
        TimeFixture fixture = timeFixture("time-flow", 30, 8);
        String other = register("time-outsider@qq.com");
        mvc.perform(auth(post("/api/v1/app-time/children/{id}/device", fixture.childId), fixture.child)
                .contentType(MediaType.APPLICATION_JSON).content(deviceBody(UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/v1/app-time/children/{id}/device", fixture.childId), other)
                .contentType(MediaType.APPLICATION_JSON).content(deviceBody(UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/v1/rewards/items/{id}/redeem", fixture.reward), fixture.child)
                .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isForbidden());

        String request = requestBody();
        String receipt = value(mvc.perform(timeAuth(post("/api/v1/rewards/items/{id}/redeem", fixture.reward), fixture)
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn(), "$.id");
        mvc.perform(timeAuth(post("/api/v1/rewards/items/{id}/redeem", fixture.reward), fixture)
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(receipt));
        assertBalance(fixture, 30);
        mvc.perform(timeAuth(get("/api/v1/app-time/grants"), fixture))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(timeAuth(post("/api/v1/app-time/grants/{id}/received", receipt), fixture))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TIME_NOT_APPROVED"));
        mvc.perform(auth(post("/api/v1/rewards/redemptions/{id}/provide", receipt), fixture.child))
                .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/v1/rewards/redemptions/{id}/provide", receipt), other))
                .andExpect(status().isForbidden());
        for (int repeat = 0; repeat < 2; repeat++)
            mvc.perform(auth(post("/api/v1/rewards/redemptions/{id}/provide", receipt), fixture.parent))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertBalance(fixture, 22);
        mvc.perform(auth(get("/api/v1/app-time/grants"), fixture.child)
                .header("X-Time-Device-Id", fixture.deviceId).header("X-Time-Device-Key", "x".repeat(43)))
                .andExpect(status().isForbidden());
        mvc.perform(timeAuth(get("/api/v1/app-time/grants"), fixture))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(receipt))
                .andExpect(jsonPath("$[0].minutes").value(5));
        mvc.perform(auth(post("/api/v1/rewards/redemptions/{id}/confirm", receipt), fixture.child))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TIME_DELIVERY_REQUIRED"));
        mvc.perform(auth(post("/api/v1/app-time/children/{id}/device", fixture.childId), fixture.parent)
                .contentType(MediaType.APPLICATION_JSON).content(deviceBody(UUID.randomUUID().toString())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNCLAIMED_TIME"));
        for (int repeat = 0; repeat < 2; repeat++)
            mvc.perform(timeAuth(post("/api/v1/app-time/grants/{id}/received", receipt), fixture))
                    .andExpect(status().isOk());
        mvc.perform(timeAuth(get("/api/v1/app-time/grants"), fixture))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        String cancelled = requestTime(fixture), rejected = requestTime(fixture);
        mvc.perform(auth(post("/api/v1/rewards/redemptions/{id}/cancel", cancelled), fixture.child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(auth(post("/api/v1/rewards/redemptions/{id}/reject", rejected), fixture.parent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(auth(post("/api/v1/rewards/redemptions/{id}/provide", rejected), fixture.parent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertBalance(fixture, 22);
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM point_action WHERE child_id = ? AND kind = 'REWARD_REDEMPTION'",
                Integer.class, fixture.childId));
        mvc.perform(auth(post("/api/v1/app-time/children/{id}/device", fixture.childId), fixture.parent)
                .contentType(MediaType.APPLICATION_JSON).content(deviceBody(UUID.randomUUID().toString())))
                .andExpect(status().isOk());
        mvc.perform(timeAuth(get("/api/v1/app-time/grants"), fixture)).andExpect(status().isForbidden());
    }

    @Test
    void concurrentTimeApprovalsCannotOverspendAndDuplicateApprovalIsIdempotent() throws Exception {
        TimeFixture fixture = timeFixture("time-parallel", 20, 15);
        String first = requestTime(fixture), second = requestTime(fixture);
        String parentId = value(mvc.perform(auth(get("/api/v1/auth/me"), fixture.parent))
                .andExpect(status().isOk()).andReturn(), "$.id");
        CurrentUser parent = new CurrentUser(parentId, Account.Role.GUARDIAN);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> one = pool.submit(() -> tryApprove(parent, first, ready, start));
            Future<Boolean> two = pool.submit(() -> tryApprove(parent, second, ready, start));
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            boolean firstSucceeded = one.get(10, TimeUnit.SECONDS);
            int total = (firstSucceeded ? 1 : 0) + (two.get(10, TimeUnit.SECONDS) ? 1 : 0);
            org.junit.jupiter.api.Assertions.assertEquals(1, total);
            rewards.markProvided(parent, firstSucceeded ? first : second);
            assertBalance(fixture, 5);
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reward_redemption WHERE child_id = ? AND status = 'APPROVED'",
                    Integer.class, fixture.childId));
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    private boolean tryApprove(CurrentUser parent, String receipt, CountDownLatch ready,
                               CountDownLatch start) throws InterruptedException {
        ready.countDown(); start.await();
        try { rewards.markProvided(parent, receipt); return true; }
        catch (ApiProblem problem) {
            if (!"INSUFFICIENT_POINTS".equals(problem.code())) throw problem;
            return false;
        }
    }
    private TimeFixture timeFixture(String prefix, int balance, int cost) throws Exception {
        String parent = register(prefix + "@qq.com");
        String username = prefix.replace('-', '_');
        String childId = value(mvc.perform(auth(post("/api/v1/family/children"), parent)
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + username +
                        "\",\"password\":\"child-pass-123\",\"displayName\":\"时间测试\"}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        String child = value(mvc.perform(post("/api/v1/auth/child-sessions")
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + username +
                        "\",\"password\":\"child-pass-123\"}"))
                .andExpect(status().isOk()).andReturn(), "$.token");
        String item = value(mvc.perform(auth(post("/api/v1/points/children/{id}/items", childId), parent)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"测试准备积分\",\"points\":" + balance + "}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        mvc.perform(auth(post("/api/v1/points/items/{id}/grant", item), parent)
                .contentType(MediaType.APPLICATION_JSON).content(requestBody())).andExpect(status().isOk());
        String reward = value(mvc.perform(auth(post("/api/v1/rewards/children/{id}/items", childId), parent)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"限制应用使用时间\",\"cost\":" + cost +
                        ",\"rewardKind\":\"APP_TIME\",\"timeMinutes\":5}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        String deviceId = UUID.randomUUID().toString();
        String key = value(mvc.perform(auth(post("/api/v1/app-time/children/{id}/device", childId), parent)
                .contentType(MediaType.APPLICATION_JSON).content(deviceBody(deviceId)))
                .andExpect(status().isOk()).andReturn(), "$.deviceKey");
        return new TimeFixture(parent, child, childId, reward, deviceId, key);
    }
    private String requestTime(TimeFixture fixture) throws Exception {
        return value(mvc.perform(timeAuth(post("/api/v1/rewards/items/{id}/redeem", fixture.reward), fixture)
                .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isCreated()).andReturn(), "$.id");
    }
    private MockHttpServletRequestBuilder timeAuth(MockHttpServletRequestBuilder request, TimeFixture fixture) {
        return auth(request, fixture.child).header("X-Time-Device-Id", fixture.deviceId)
                .header("X-Time-Device-Key", fixture.deviceKey);
    }
    private void assertBalance(TimeFixture fixture, long balance) {
        org.junit.jupiter.api.Assertions.assertEquals(balance, jdbc.queryForObject(
                "SELECT balance FROM point_account WHERE child_id = ?", Long.class, fixture.childId));
    }
    private String requestBody() { return "{\"requestId\":\"" + UUID.randomUUID() + "\"}"; }
    private String deviceBody(String deviceId) { return "{\"deviceId\":\"" + deviceId + "\",\"deviceName\":\"Test phone\"}"; }
    private record TimeFixture(String parent, String child, String childId, String reward, String deviceId, String deviceKey) {}

    @Test
    void concurrentRedemptionsCannotOverspend() throws Exception {
        String guardian = register("parallel-parent@qq.com");
        String childId = value(mvc.perform(auth(post("/api/v1/family/children"), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"parallel_child\",\"password\":\"child-pass-123\",\"displayName\":\"并发测试\"}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        String pointItem = value(mvc.perform(auth(post("/api/v1/points/children/{id}/items", childId), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"准备积分\",\"points\":20}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        mvc.perform(auth(post("/api/v1/points/items/{id}/grant", pointItem), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk());
        String first = createReward(guardian, childId, "奖励一", 15);
        String second = createReward(guardian, childId, "奖励二", 15);
        CurrentUser child = new CurrentUser(childId, Account.Role.CHILD);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> one = pool.submit(() -> tryRedeem(child, first, ready, start));
            Future<Boolean> two = pool.submit(() -> tryRedeem(child, second, ready, start));
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int successes = (one.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (two.get(10, TimeUnit.SECONDS) ? 1 : 0);
            org.junit.jupiter.api.Assertions.assertEquals(1, successes);
            org.junit.jupiter.api.Assertions.assertEquals(5L, jdbc.queryForObject(
                    "SELECT balance FROM point_account WHERE child_id = ?", Long.class, childId));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reward_redemption WHERE child_id = ?", Integer.class, childId));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private boolean tryRedeem(CurrentUser child, String rewardId, CountDownLatch ready,
                              CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            rewards.redeem(child, rewardId, UUID.randomUUID().toString());
            return true;
        } catch (ApiProblem problem) {
            if (!"INSUFFICIENT_POINTS".equals(problem.code())) throw problem;
            return false;
        }
    }

    @Test
    void redemptionKeepsReceiptAndSpendsOnlyOnce() throws Exception {
        String guardian = register("reward-parent@qq.com");
        String secondGuardian = register("reward-second@163.com");
        String childId = value(mvc.perform(auth(post("/api/v1/family/children"), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reward_child\",\"password\":\"child-pass-123\",\"displayName\":\"小星\"}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        String child = value(mvc.perform(post("/api/v1/auth/child-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reward_child\",\"password\":\"child-pass-123\"}"))
                .andExpect(status().isOk()).andReturn(), "$.token");

        mvc.perform(auth(get("/api/v1/rewards/children/{id}", childId), secondGuardian))
                .andExpect(status().isForbidden());
        String rewardId = createReward(guardian, childId, "玩 20 分钟游戏", 8);
        mvc.perform(auth(post("/api/v1/rewards/children/{id}/items", childId), child)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权奖励\",\"cost\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/v1/rewards/items/{id}/redeem", rewardId), child)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_POINTS"));

        String pointItem = value(mvc.perform(auth(post("/api/v1/points/children/{id}/items", childId), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"完成阅读\",\"points\":30}"))
                .andExpect(status().isCreated()).andReturn(), "$.id");
        mvc.perform(auth(post("/api/v1/points/items/{id}/grant", pointItem), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(auth(post("/api/v1/rewards/items/{id}/redeem", rewardId), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());

        String requestId = UUID.randomUUID().toString();
        String body = "{\"requestId\":\"" + requestId + "\"}";
        MvcResult created = mvc.perform(auth(post("/api/v1/rewards/items/{id}/redeem", rewardId), child)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.cost").value(8)).andReturn();
        String receiptId = value(created, "$.id");
        String actionId = value(created, "$.actionId");
        mvc.perform(auth(post("/api/v1/rewards/items/{id}/redeem", rewardId), child)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(receiptId));
        mvc.perform(auth(get("/api/v1/rewards/children/{id}", childId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(22))
                .andExpect(jsonPath("$.redemptions[0].rewardName").value("玩 20 分钟游戏"));
        mvc.perform(auth(get("/api/v1/points/children/{id}", childId), guardian))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(22))
                .andExpect(jsonPath("$.actions[0].kind").value("REWARD_REDEMPTION"));
        mvc.perform(auth(post("/api/v1/points/actions/{id}/reverse", actionId), guardian))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REDEMPTION_RECEIPT"));

        String otherReward = createReward(guardian, childId, "看电影", 10);
        mvc.perform(auth(post("/api/v1/rewards/items/{id}/redeem", otherReward), child)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(auth(post("/api/v1/rewards/redemptions/{id}/provide", receiptId), child))
                .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/v1/rewards/redemptions/{id}/confirm", receiptId), guardian))
                .andExpect(status().isForbidden());

        String invite = value(mvc.perform(auth(post("/api/v1/family/children/{id}/invites", childId), guardian))
                .andExpect(status().isOk()).andReturn(), "$.code");
        mvc.perform(auth(post("/api/v1/family/invites/redeem"), secondGuardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + invite + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(auth(post("/api/v1/rewards/redemptions/{id}/provide", receiptId), secondGuardian))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OFFERED"));
        mvc.perform(auth(post("/api/v1/rewards/redemptions/{id}/confirm", receiptId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"));
        mvc.perform(auth(post("/api/v1/rewards/items/{id}/archive", rewardId), secondGuardian))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(auth(get("/api/v1/rewards/children/{id}", childId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.redemptions[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.redemptions[0].rewardName").value("玩 20 分钟游戏"));
        mvc.perform(auth(post("/api/v1/rewards/items/{id}/redeem", rewardId), child)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict());

        String costly = createReward(guardian, childId, "昂贵奖励", 23);
        mvc.perform(auth(post("/api/v1/rewards/items/{id}/redeem", costly), child)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict());

        String exactCost = createReward(guardian, childId, "刚好够分", 22);
        String dueDeduction = value(mvc.perform(auth(post("/api/v1/points/children/{id}/deductions", childId), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"points\":5,\"reason\":\"测试到期扣分\",\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andReturn(), "$.id");
        jdbc.update("UPDATE point_action SET due_at = ? WHERE id = ?", System.currentTimeMillis() - 1,
                dueDeduction);
        mvc.perform(auth(post("/api/v1/rewards/items/{id}/redeem", exactCost), child)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_POINTS"));
        mvc.perform(auth(get("/api/v1/points/children/{id}", childId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(17));
        mvc.perform(auth(post("/api/v1/points/actions/{id}/reverse", dueDeduction), guardian))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVERSED"));

        mvc.perform(auth(post("/api/v1/rewards/items/{id}/restore", rewardId), secondGuardian))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        mvc.perform(auth(get("/api/v1/rewards/children/{id}", childId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.redemptions[0].id").value(receiptId));

        String cheap = createReward(guardian, childId, "小奖励", 1);
        for (int index = 0; index < 21; index++) {
            mvc.perform(auth(post("/api/v1/rewards/items/{id}/redeem", cheap), child)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(auth(get("/api/v1/rewards/children/{id}", childId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(1))
                .andExpect(jsonPath("$.redemptions.length()").value(20))
                .andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(auth(get("/api/v1/rewards/children/{id}/redemptions?offset=20", childId), child))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    private String createReward(String guardian, String childId, String name, int cost) throws Exception {
        return value(mvc.perform(auth(post("/api/v1/rewards/children/{id}/items", childId), guardian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"cost\":" + cost + "}"))
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
