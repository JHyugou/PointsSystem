package com.pointshome.api.rewards;

import com.pointshome.api.auth.Account;
import com.pointshome.api.auth.AccountStore;
import com.pointshome.api.auth.CurrentUser;
import com.pointshome.api.common.ApiProblem;
import com.pointshome.api.points.PointService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RewardService {
    private static final int HISTORY_PAGE_SIZE = 20;
    private static final RowMapper<RewardItem> ITEM = (rs, row) -> new RewardItem(
            rs.getString("id"), rs.getString("child_id"), rs.getString("name"),
            rs.getString("description"), rs.getInt("cost"), rs.getBoolean("active"),
            rs.getLong("created_at"), rs.getObject("archived_at", Long.class),
            rs.getString("reward_kind"), rs.getObject("time_minutes", Integer.class));
    private static final RowMapper<Redemption> REDEMPTION = (rs, row) -> new Redemption(
            rs.getString("id"), rs.getString("child_id"), rs.getString("reward_item_id"),
            rs.getString("action_id"), rs.getString("reward_name"),
            rs.getString("reward_description"), rs.getInt("cost"), rs.getString("status"),
            rs.getLong("requested_at"), rs.getObject("offered_at", Long.class),
            rs.getObject("received_at", Long.class), rs.getString("reward_kind"),
            rs.getObject("time_minutes", Integer.class), rs.getString("device_id"), rs.getString("request_id"));

    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    private final PointService points;
    private final TimeDeviceService devices;

    public RewardService(JdbcTemplate jdbc, AccountStore accounts, PointService points, TimeDeviceService devices) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.points = points;
        this.devices = devices;
    }

    @Transactional
    public Overview overview(CurrentUser user, String childId) {
        requireAccess(user, childId);
        long balance = points.currentBalance(childId);
        List<RewardItem> items = user.role() == Account.Role.GUARDIAN
                ? jdbc.query("SELECT * FROM reward_item WHERE child_id = ? ORDER BY created_at DESC, id DESC",
                        ITEM, childId)
                : jdbc.query("SELECT * FROM reward_item WHERE child_id = ? AND active = TRUE ORDER BY created_at DESC, id DESC",
                        ITEM, childId);
        HistoryPage history = historyRows(childId, 0);
        return new Overview(balance, items, history.entries(), history.hasMore());
    }

    public HistoryPage history(CurrentUser user, String childId, int offset) {
        requireAccess(user, childId);
        if (offset < 0 || offset > 1_000_000) throw badInput();
        return historyRows(childId, offset);
    }

    @Transactional
    public RewardItem create(CurrentUser user, String childId, String name, String description, int cost) {
        return create(user, childId, name, description, cost, "STANDARD", null);
    }

    @Transactional
    public RewardItem create(CurrentUser user, String childId, String name, String description, int cost,
                             String kind, Integer minutes) {
        requireGuardian(user, childId);
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 80 || cost < 1 || cost > 100_000)
            throw badInput();
        String rewardKind = kind == null ? "STANDARD" : kind;
        if (!("STANDARD".equals(rewardKind) && minutes == null)
                && !("APP_TIME".equals(rewardKind) && minutes != null && minutes >= 1 && minutes <= 1440))
            throw badInput();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO reward_item (id, child_id, created_by, name, description, cost, active, created_at,
                                         reward_kind, time_minutes)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?)
                """, id, childId, user.id(), cleanName, cleanDescription(description), cost,
                System.currentTimeMillis(), rewardKind, minutes);
        return item(id, false);
    }

    @Transactional
    public RewardItem archive(CurrentUser user, String rewardId) {
        RewardItem before = item(rewardId, false);
        requireGuardian(user, before.childId());
        RewardItem reward = item(rewardId, true);
        if (reward.active()) {
            jdbc.update("UPDATE reward_item SET active = FALSE, archived_at = ? WHERE id = ?",
                    System.currentTimeMillis(), rewardId);
        }
        return item(rewardId, false);
    }

    @Transactional
    public RewardItem restore(CurrentUser user, String rewardId) {
        RewardItem before = item(rewardId, false);
        requireGuardian(user, before.childId());
        RewardItem reward = item(rewardId, true);
        if (!reward.active()) {
            jdbc.update("UPDATE reward_item SET active = TRUE, archived_at = NULL WHERE id = ?",
                    rewardId);
        }
        return item(rewardId, false);
    }

    @Transactional
    public Redemption redeem(CurrentUser user, String rewardId, String requestId) {
        return redeem(user, rewardId, requestId, null, null);
    }

    @Transactional
    public Redemption redeem(CurrentUser user, String rewardId, String requestId, String deviceId, String deviceKey) {
        if (user == null || user.role() != Account.Role.CHILD) throw forbidden();
        validateRequestId(requestId);
        Redemption existing = byRequest(user.id(), requestId);
        if (existing != null) return sameReward(existing, rewardId);

        points.currentBalance(user.id());
        existing = byRequest(user.id(), requestId);
        if (existing != null) return sameReward(existing, rewardId);
        RewardItem reward = item(rewardId, true);
        if (!reward.childId().equals(user.id())) throw forbidden();
        if (!reward.active())
            throw new ApiProblem(HttpStatus.CONFLICT, "REWARD_UNAVAILABLE", "这个奖励已停止兑换");

        if ("APP_TIME".equals(reward.rewardKind())) {
            devices.requireDevice(user, deviceId, deviceKey);
            if (points.currentBalance(user.id()) < reward.cost())
                throw new ApiProblem(HttpStatus.CONFLICT, "INSUFFICIENT_POINTS", "积分不足，暂时无法申请兑换");
            Integer pending = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM reward_redemption WHERE child_id = ? AND status = 'PENDING_APPROVAL'
                    """, Integer.class, user.id());
            if (pending != null && pending >= 20)
                throw new ApiProblem(HttpStatus.CONFLICT, "TOO_MANY_REQUESTS", "还有待审批申请，请先请家长处理");
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO reward_redemption (id, child_id, reward_item_id, reward_name, reward_description,
                    cost, status, requested_at, reward_kind, time_minutes, device_id, request_id)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, 'APP_TIME', ?, ?, ?)
                    """, id, user.id(), reward.id(), reward.name(), reward.description(), reward.cost(),
                    System.currentTimeMillis(), reward.timeMinutes(), deviceId, requestId);
            return redemption(id, false);
        }

        PointService.Action action = points.spendForReward(user.id(), user.id(), reward.cost(),
                requestId, reward.name());
        existing = byAction(action.id());
        if (existing != null) return sameReward(existing, rewardId);

        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO reward_redemption (id, child_id, reward_item_id, action_id,
                                               reward_name, reward_description, cost, status, requested_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?)
                """, id, user.id(), reward.id(), action.id(), reward.name(), reward.description(),
                reward.cost(), System.currentTimeMillis(), requestId);
        return redemption(id, false);
    }

    @Transactional
    public Redemption markProvided(CurrentUser user, String redemptionId) {
        Redemption before = redemption(redemptionId, false);
        requireGuardian(user, before.childId());
        points.currentBalance(before.childId());
        Redemption record = redemption(redemptionId, true);
        if ("APP_TIME".equals(record.rewardKind())) {
            if ("PENDING_APPROVAL".equals(record.status())) {
                if (!devices.isBound(record.childId(), record.deviceId()))
                    throw new ApiProblem(HttpStatus.CONFLICT, "TIME_DEVICE_CHANGED", "绑定手机已变更，请拒绝这份申请后重新申请");
                PointService.Action action = points.spendForReward(record.childId(), record.childId(),
                        record.cost(), record.requestId(), record.rewardName());
                jdbc.update("""
                        UPDATE reward_redemption SET status = 'APPROVED', action_id = ?, offered_at = ?, offered_by = ?
                        WHERE id = ?
                        """, action.id(), System.currentTimeMillis(), user.id(), redemptionId);
            }
            return redemption(redemptionId, false);
        }
        if ("REQUESTED".equals(record.status())) {
            jdbc.update("""
                    UPDATE reward_redemption SET status = 'OFFERED', offered_at = ?, offered_by = ? WHERE id = ?
                    """, System.currentTimeMillis(), user.id(), redemptionId);
        }
        return redemption(redemptionId, false);
    }

    @Transactional
    public Redemption confirmReceived(CurrentUser user, String redemptionId) {
        Redemption before = redemption(redemptionId, false);
        if (user == null || user.role() != Account.Role.CHILD || !user.id().equals(before.childId()))
            throw forbidden();
        if ("APP_TIME".equals(before.rewardKind()))
            throw new ApiProblem(HttpStatus.CONFLICT, "TIME_DELIVERY_REQUIRED", "时间奖励由已绑定手机领取，不能手动确认");
        Redemption record = redemption(redemptionId, true);
        if (!"RECEIVED".equals(record.status())) {
            jdbc.update("""
                    UPDATE reward_redemption SET status = 'RECEIVED', received_at = ? WHERE id = ?
                    """, System.currentTimeMillis(), redemptionId);
        }
        return redemption(redemptionId, false);
    }

    private HistoryPage historyRows(String childId, int offset) {
        List<Redemption> rows = jdbc.query("""
                SELECT * FROM reward_redemption WHERE child_id = ?
                ORDER BY requested_at DESC, id DESC LIMIT ? OFFSET ?
                """, REDEMPTION, childId, HISTORY_PAGE_SIZE + 1, offset);
        return new HistoryPage(rows.stream().limit(HISTORY_PAGE_SIZE).toList(),
                rows.size() > HISTORY_PAGE_SIZE);
    }

    private RewardItem item(String id, boolean lock) {
        return jdbc.query("SELECT * FROM reward_item WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                ITEM, id).stream().findFirst().orElseThrow(this::notFound);
    }

    private Redemption redemption(String id, boolean lock) {
        return jdbc.query("SELECT * FROM reward_redemption WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                REDEMPTION, id).stream().findFirst().orElseThrow(this::notFound);
    }

    private Redemption byAction(String actionId) {
        return jdbc.query("SELECT * FROM reward_redemption WHERE action_id = ?", REDEMPTION, actionId)
                .stream().findFirst().orElse(null);
    }

    private Redemption byRequest(String actorId, String requestId) {
        return jdbc.query("""
                SELECT * FROM reward_redemption WHERE child_id = ? AND request_id = ?
                """, REDEMPTION, actorId, requestId).stream().findFirst().orElse(null);
    }

    @Transactional
    public Redemption reject(CurrentUser user, String redemptionId) {
        Redemption before = redemption(redemptionId, false);
        requireGuardian(user, before.childId());
        points.currentBalance(before.childId());
        Redemption record = redemption(redemptionId, true);
        if (!"APP_TIME".equals(record.rewardKind())) throw forbidden();
        if ("PENDING_APPROVAL".equals(record.status()))
            jdbc.update("UPDATE reward_redemption SET status = 'REJECTED' WHERE id = ?", redemptionId);
        return redemption(redemptionId, false);
    }

    @Transactional
    public Redemption cancelTimeRequest(CurrentUser user, String redemptionId) {
        Redemption before = redemption(redemptionId, false);
        if (user == null || user.role() != Account.Role.CHILD || !user.id().equals(before.childId())) throw forbidden();
        points.currentBalance(before.childId());
        Redemption record = redemption(redemptionId, true);
        if (!"APP_TIME".equals(record.rewardKind())) throw forbidden();
        if ("PENDING_APPROVAL".equals(record.status()))
            jdbc.update("UPDATE reward_redemption SET status = 'CANCELLED' WHERE id = ?", redemptionId);
        return redemption(redemptionId, false);
    }

    public List<TimeGrant> timeGrants(CurrentUser user, String deviceId, String deviceKey) {
        devices.requireDevice(user, deviceId, deviceKey);
        return jdbc.query("""
                SELECT id, time_minutes FROM reward_redemption WHERE child_id = ? AND device_id = ?
                AND reward_kind = 'APP_TIME' AND status = 'APPROVED' ORDER BY offered_at, id LIMIT 50
                """, (rs, row) -> new TimeGrant(rs.getString("id"), rs.getInt("time_minutes")), user.id(), deviceId);
    }

    @Transactional
    public void acknowledgeTime(CurrentUser user, String id, String deviceId, String deviceKey) {
        devices.requireDevice(user, deviceId, deviceKey);
        Redemption record = redemption(id, true);
        if (!record.childId().equals(user.id()) || !"APP_TIME".equals(record.rewardKind())
                || !deviceId.equals(record.deviceId())) throw forbidden();
        if ("RECEIVED".equals(record.status())) return;
        if (!"APPROVED".equals(record.status()))
            throw new ApiProblem(HttpStatus.CONFLICT, "TIME_NOT_APPROVED", "时间奖励尚未批准");
        jdbc.update("UPDATE reward_redemption SET status = 'RECEIVED', received_at = ? WHERE id = ?",
                System.currentTimeMillis(), id);
    }

    private Redemption sameReward(Redemption record, String rewardId) {
        if (!record.rewardItemId().equals(rewardId))
            throw new ApiProblem(HttpStatus.CONFLICT, "REQUEST_CONFLICT", "请求编号已用于其他奖励");
        return record;
    }

    private void validateRequestId(String requestId) {
        if (requestId == null || requestId.length() != 36) throw badInput();
        try { UUID.fromString(requestId); } catch (IllegalArgumentException e) { throw badInput(); }
    }

    private String cleanDescription(String description) {
        if (description == null || description.isBlank()) return null;
        String clean = description.trim();
        if (clean.length() > 300) throw badInput();
        return clean;
    }

    private void requireAccess(CurrentUser user, String childId) {
        if (user == null || !(user.role() == Account.Role.CHILD && user.id().equals(childId))
                && !(user.role() == Account.Role.GUARDIAN && accounts.isLinked(user.id(), childId)))
            throw forbidden();
    }

    private void requireGuardian(CurrentUser user, String childId) {
        if (user == null || user.role() != Account.Role.GUARDIAN || !accounts.isLinked(user.id(), childId))
            throw forbidden();
    }

    private ApiProblem forbidden() {
        return new ApiProblem(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有操作这个孩子奖励的权限");
    }

    private ApiProblem notFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "找不到这项奖励记录");
    }

    private ApiProblem badInput() {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请检查奖励名称、积分和说明");
    }

    public record RewardItem(String id, String childId, String name, String description, int cost,
                             boolean active, long createdAt, Long archivedAt, String rewardKind, Integer timeMinutes) {}
    public record Redemption(String id, String childId, String rewardItemId, String actionId,
                             String rewardName, String rewardDescription, int cost, String status,
                             long requestedAt, Long offeredAt, Long receivedAt, String rewardKind,
                             Integer timeMinutes, String deviceId, String requestId) {}
    public record TimeGrant(String id, int minutes) {}
    public record HistoryPage(List<Redemption> entries, boolean hasMore) {}
    public record Overview(long balance, List<RewardItem> items,
                           List<Redemption> redemptions, boolean hasMore) {}
}
