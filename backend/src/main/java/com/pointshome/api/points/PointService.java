package com.pointshome.api.points;

import com.pointshome.api.auth.Account;
import com.pointshome.api.auth.AccountStore;
import com.pointshome.api.auth.CurrentUser;
import com.pointshome.api.common.ApiProblem;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointService {
    public static final long DEDUCTION_DELAY_MS = 30 * 60_000L;

    private static final RowMapper<Item> ITEM = (rs, row) -> new Item(
            rs.getString("id"), rs.getString("child_id"), rs.getString("name"),
            rs.getInt("points"), rs.getObject("max_uses", Integer.class),
            rs.getObject("remaining_uses", Integer.class), rs.getObject("expires_at", Long.class),
            rs.getLong("created_at"));
    private static final RowMapper<Claim> CLAIM = (rs, row) -> new Claim(
            rs.getString("id"), rs.getString("child_id"), rs.getString("item_id"),
            rs.getString("status"), rs.getString("note"), rs.getLong("created_at"),
            rs.getObject("decided_at", Long.class));
    private static final RowMapper<Action> ACTION = (rs, row) -> new Action(
            rs.getString("id"), rs.getString("child_id"), rs.getString("item_id"),
            rs.getString("claim_id"), rs.getString("kind"), rs.getInt("amount"),
            rs.getString("status"), rs.getString("note"), rs.getObject("due_at", Long.class),
            rs.getLong("created_at"), rs.getObject("applied_at", Long.class),
            rs.getObject("reversed_at", Long.class));
    private static final RowMapper<LedgerEntry> LEDGER = (rs, row) -> new LedgerEntry(
            rs.getString("id"), rs.getString("action_id"), rs.getInt("delta"),
            rs.getString("kind"), rs.getLong("created_at"));

    private final JdbcTemplate jdbc;
    private final AccountStore accounts;

    public PointService(JdbcTemplate jdbc, AccountStore accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    public void createAccount(String childId) {
        jdbc.update("INSERT INTO point_account (child_id, balance) VALUES (?, 0)", childId);
    }

    @Transactional
    public Overview overview(CurrentUser user, String childId) {
        requireAccess(user, childId);
        long balance = lockBalance(childId);
        settleDueForChild(childId, System.currentTimeMillis());
        balance = jdbc.queryForObject("SELECT balance FROM point_account WHERE child_id = ?", Long.class, childId);
        List<Item> items = jdbc.query("SELECT * FROM point_item WHERE child_id = ? ORDER BY created_at DESC, id DESC",
                ITEM, childId);
        List<Claim> claims = jdbc.query("SELECT * FROM point_claim WHERE child_id = ? ORDER BY created_at DESC, id DESC LIMIT 50",
                CLAIM, childId);
        List<Action> actions = jdbc.query("SELECT * FROM point_action WHERE child_id = ? ORDER BY created_at DESC, id DESC LIMIT 50",
                ACTION, childId);
        List<LedgerEntry> ledger = jdbc.query("SELECT * FROM point_ledger WHERE child_id = ? ORDER BY created_at DESC, id DESC LIMIT 50",
                LEDGER, childId);
        return new Overview(balance, items, claims, actions, ledger);
    }

    @Transactional
    public long currentBalance(String childId) {
        lockBalance(childId);
        settleDueForChild(childId, System.currentTimeMillis());
        return jdbc.queryForObject("SELECT balance FROM point_account WHERE child_id = ?", Long.class, childId);
    }

    @Transactional
    public Action spendForReward(String childId, String actorId, int cost, String requestId,
                                 String rewardName) {
        if (cost < 1 || cost > 100_000) throw badInput();
        lockBalance(childId);
        settleDueForChild(childId, System.currentTimeMillis());
        Action existing = existingRequest(actorId, requestId);
        if (existing != null) {
            if (!"REWARD_REDEMPTION".equals(existing.kind()) || !existing.childId().equals(childId)
                    || existing.amount() != -cost)
                throw new ApiProblem(HttpStatus.CONFLICT, "REQUEST_CONFLICT", "请求编号已用于其他操作");
            return existing;
        }
        Long balance = jdbc.queryForObject("SELECT balance FROM point_account WHERE child_id = ?",
                Long.class, childId);
        if (balance == null || balance < cost)
            throw new ApiProblem(HttpStatus.CONFLICT, "INSUFFICIENT_POINTS", "积分不足，暂时无法兑换");
        long now = System.currentTimeMillis();
        Action action = insertAction(childId, null, null, actorId, requestId,
                "REWARD_REDEMPTION", -cost, "APPLIED", "兑换：" + rewardName, null, now);
        apply(action, actorId, now);
        return action;
    }

    @Transactional
    public Item createItem(CurrentUser user, String childId, String name, int points,
                           Integer maxUses, Integer durationDays) {
        requireGuardian(user, childId);
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 80 || points == 0 || Math.abs((long) points) > 100_000
                || (maxUses != null && (maxUses < 1 || maxUses > 10_000))
                || (durationDays != null && (durationDays < 1 || durationDays > 3_650))) {
            throw badInput();
        }
        long now = System.currentTimeMillis();
        Long expiresAt = durationDays == null ? null : now + durationDays * 86_400_000L;
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO point_item (id, child_id, created_by, name, points, max_uses,
                                        remaining_uses, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, childId, user.id(), cleanName, points, maxUses, maxUses, expiresAt, now);
        return item(id, false);
    }

    @Transactional
    public Action grant(CurrentUser user, String itemId, String note, String requestId) {
        Item before = item(itemId, false);
        requireGuardian(user, before.childId());
        lockBalance(before.childId());
        Action existing = existingRequest(user.id(), requestId);
        if (existing != null) {
            if (!Objects.equals(existing.itemId(), itemId) || !"ITEM_GRANT".equals(existing.kind()))
                throw new ApiProblem(HttpStatus.CONFLICT, "REQUEST_CONFLICT", "请求编号已用于其他操作");
            return existing;
        }
        long now = System.currentTimeMillis();
        Item item = item(itemId, true);
        reserve(item, now);
        String status = item.points() < 0 ? "PENDING" : "APPLIED";
        Long dueAt = item.points() < 0 ? now + DEDUCTION_DELAY_MS : null;
        Action action = insertAction(item.childId(), item.id(), null, user.id(), requestId,
                "ITEM_GRANT", item.points(), status, cleanNote(note), dueAt, now);
        if ("APPLIED".equals(status)) apply(action, user.id(), now);
        return action;
    }

    @Transactional
    public Action deduct(CurrentUser user, String childId, int points, String reason, String requestId) {
        requireGuardian(user, childId);
        if (points < 1 || points > 100_000 || reason == null || reason.trim().isEmpty()) throw badInput();
        lockBalance(childId);
        Action existing = existingRequest(user.id(), requestId);
        if (existing != null) {
            if (!"DIRECT_DEDUCTION".equals(existing.kind()) || !existing.childId().equals(childId)
                    || existing.amount() != -points)
                throw new ApiProblem(HttpStatus.CONFLICT, "REQUEST_CONFLICT", "请求编号已用于其他操作");
            return existing;
        }
        long now = System.currentTimeMillis();
        return insertAction(childId, null, null, user.id(), requestId, "DIRECT_DEDUCTION",
                -points, "PENDING", cleanNote(reason), now + DEDUCTION_DELAY_MS, now);
    }

    @Transactional
    public Claim claim(CurrentUser user, String itemId, String note) {
        if (user.role() != Account.Role.CHILD) throw forbidden();
        Item item = item(itemId, true);
        if (!item.childId().equals(user.id())) throw forbidden();
        long now = System.currentTimeMillis();
        ensureAvailable(item, now);
        if (item.points() < 0) throw new ApiProblem(HttpStatus.FORBIDDEN, "NEGATIVE_ITEM", "扣分项目由家长处理");
        Integer pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM point_claim WHERE item_id = ? AND child_id = ? AND status = 'PENDING'
                """, Integer.class, itemId, user.id());
        if (pending != null && pending > 0)
            throw new ApiProblem(HttpStatus.CONFLICT, "CLAIM_PENDING", "这个项目已有待审批申报");
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO point_claim (id, child_id, item_id, status, note, created_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?)
                """, id, user.id(), itemId, cleanNote(note), now);
        return claim(id, false);
    }

    @Transactional
    public Action approve(CurrentUser user, String claimId) {
        Claim before = claim(claimId, false);
        requireGuardian(user, before.childId());
        lockBalance(before.childId());
        Claim claim = claim(claimId, true);
        if ("APPROVED".equals(claim.status())) {
            return jdbc.query("SELECT * FROM point_action WHERE claim_id = ?", ACTION, claimId).get(0);
        }
        if (!"PENDING".equals(claim.status())) throw invalidState();
        Item item = item(claim.itemId(), true);
        long now = System.currentTimeMillis();
        reserve(item, now);
        jdbc.update("UPDATE point_claim SET status = 'APPROVED', decided_at = ?, decided_by = ? WHERE id = ?",
                now, user.id(), claimId);
        Action action = insertAction(claim.childId(), item.id(), claimId, user.id(), null,
                "CLAIM_APPROVAL", item.points(), "APPLIED", claim.note(), null, now);
        apply(action, user.id(), now);
        return action;
    }

    @Transactional
    public Claim reject(CurrentUser user, String claimId) {
        Claim before = claim(claimId, false);
        requireGuardian(user, before.childId());
        Claim claim = claim(claimId, true);
        if ("REJECTED".equals(claim.status())) return claim;
        if (!"PENDING".equals(claim.status())) throw invalidState();
        jdbc.update("UPDATE point_claim SET status = 'REJECTED', decided_at = ?, decided_by = ? WHERE id = ?",
                System.currentTimeMillis(), user.id(), claimId);
        return claim(claimId, false);
    }

    @Transactional
    public Action reverse(CurrentUser user, String actionId) {
        Action before = action(actionId, false);
        requireGuardian(user, before.childId());
        if ("REWARD_REDEMPTION".equals(before.kind()))
            throw new ApiProblem(HttpStatus.CONFLICT, "REDEMPTION_RECEIPT", "兑换记录不能由家长撤销");
        lockBalance(before.childId());
        Action action = action(actionId, true);
        long now = System.currentTimeMillis();
        if ("CANCELLED".equals(action.status()) || "REVERSED".equals(action.status())) return action;
        if ("PENDING".equals(action.status()) && action.dueAt() <= now) {
            apply(action, user.id(), now);
            action = action(actionId, true);
        }
        if ("PENDING".equals(action.status())) {
            jdbc.update("UPDATE point_action SET status = 'CANCELLED', reversed_at = ?, reversed_by = ? WHERE id = ?",
                    now, user.id(), actionId);
        } else if ("APPLIED".equals(action.status())) {
            String original = jdbc.queryForObject("""
                    SELECT id FROM point_ledger WHERE action_id = ? AND kind = 'APPLY'
                    """, String.class, actionId);
            jdbc.update("UPDATE point_account SET balance = balance - ? WHERE child_id = ?",
                    action.amount(), action.childId());
            jdbc.update("""
                    INSERT INTO point_ledger (id, child_id, action_id, actor_id, delta, kind, reverses_ledger_id, created_at)
                    VALUES (?, ?, ?, ?, ?, 'REVERSAL', ?, ?)
                    """, UUID.randomUUID().toString(), action.childId(), actionId, user.id(),
                    -action.amount(), original, now);
            jdbc.update("UPDATE point_action SET status = 'REVERSED', reversed_at = ?, reversed_by = ? WHERE id = ?",
                    now, user.id(), actionId);
        } else throw invalidState();
        if (action.itemId() != null) {
            jdbc.update("""
                    UPDATE point_item SET remaining_uses = remaining_uses + 1
                    WHERE id = ? AND remaining_uses IS NOT NULL
                    """, action.itemId());
        }
        return action(actionId, false);
    }

    public List<String> dueActionIds() {
        return jdbc.queryForList("""
                SELECT id FROM point_action WHERE status = 'PENDING' AND due_at <= ?
                ORDER BY due_at LIMIT 50
                """, String.class, System.currentTimeMillis());
    }

    @Transactional
    public void settle(String actionId) {
        Action before = action(actionId, false);
        lockBalance(before.childId());
        Action action = action(actionId, true);
        long now = System.currentTimeMillis();
        if ("PENDING".equals(action.status()) && action.dueAt() <= now) apply(action, actionActor(actionId), now);
    }

    private void settleDueForChild(String childId, long now) {
        List<String> ids = jdbc.queryForList("""
                SELECT id FROM point_action WHERE child_id = ? AND status = 'PENDING' AND due_at <= ?
                ORDER BY due_at LIMIT 50
                """, String.class, childId, now);
        for (String id : ids) {
            Action action = action(id, true);
            if ("PENDING".equals(action.status())) apply(action, actionActor(id), now);
        }
    }

    private void apply(Action action, String actorId, long now) {
        jdbc.update("UPDATE point_account SET balance = balance + ? WHERE child_id = ?",
                action.amount(), action.childId());
        jdbc.update("""
                INSERT INTO point_ledger (id, child_id, action_id, actor_id, delta, kind, created_at)
                VALUES (?, ?, ?, ?, ?, 'APPLY', ?)
                """, UUID.randomUUID().toString(), action.childId(), action.id(), actorId,
                action.amount(), now);
        jdbc.update("UPDATE point_action SET status = 'APPLIED', applied_at = ? WHERE id = ?", now, action.id());
    }

    private Action insertAction(String childId, String itemId, String claimId, String actorId,
                                String requestId, String kind, int amount, String status,
                                String note, Long dueAt, long now) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO point_action (id, child_id, item_id, claim_id, actor_id, request_id,
                                          kind, amount, status, note, due_at, created_at, applied_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, childId, itemId, claimId, actorId, requestId, kind, amount,
                status, note, dueAt, now, "APPLIED".equals(status) ? now : null);
        return action(id, false);
    }

    private void reserve(Item item, long now) {
        ensureAvailable(item, now);
        if (item.remainingUses() != null)
            jdbc.update("UPDATE point_item SET remaining_uses = remaining_uses - 1 WHERE id = ?", item.id());
    }

    private void ensureAvailable(Item item, long now) {
        if ((item.expiresAt() != null && item.expiresAt() <= now)
                || (item.remainingUses() != null && item.remainingUses() <= 0))
            throw new ApiProblem(HttpStatus.CONFLICT, "ITEM_UNAVAILABLE", "项目已到期或次数已用完");
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

    private Item item(String id, boolean lock) {
        return jdbc.query("SELECT * FROM point_item WHERE id = ?" + (lock ? " FOR UPDATE" : ""), ITEM, id)
                .stream().findFirst().orElseThrow(this::notFound);
    }

    private Claim claim(String id, boolean lock) {
        return jdbc.query("SELECT * FROM point_claim WHERE id = ?" + (lock ? " FOR UPDATE" : ""), CLAIM, id)
                .stream().findFirst().orElseThrow(this::notFound);
    }

    private Action action(String id, boolean lock) {
        return jdbc.query("SELECT * FROM point_action WHERE id = ?" + (lock ? " FOR UPDATE" : ""), ACTION, id)
                .stream().findFirst().orElseThrow(this::notFound);
    }

    private Action existingRequest(String actorId, String requestId) {
        if (requestId == null) throw badInput();
        try { UUID.fromString(requestId); } catch (IllegalArgumentException e) { throw badInput(); }
        return jdbc.query("SELECT * FROM point_action WHERE actor_id = ? AND request_id = ?", ACTION,
                actorId, requestId).stream().findFirst().orElse(null);
    }

    private long lockBalance(String childId) {
        List<Long> rows = jdbc.queryForList("SELECT balance FROM point_account WHERE child_id = ? FOR UPDATE",
                Long.class, childId);
        if (rows.isEmpty()) throw notFound();
        return rows.get(0);
    }

    private String actionActor(String id) {
        return jdbc.queryForObject("SELECT actor_id FROM point_action WHERE id = ?", String.class, id);
    }

    private String cleanNote(String note) {
        if (note == null || note.isBlank()) return null;
        String clean = note.trim();
        if (clean.length() > 300) throw badInput();
        return clean;
    }

    private ApiProblem notFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "找不到这项积分记录");
    }

    private ApiProblem forbidden() {
        return new ApiProblem(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有操作这个孩子积分的权限");
    }

    private ApiProblem invalidState() {
        return new ApiProblem(HttpStatus.CONFLICT, "INVALID_STATE", "这项操作当前无法审批或撤销");
    }

    private ApiProblem badInput() {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请检查积分、次数和文字内容");
    }

    public record Item(String id, String childId, String name, int points, Integer maxUses,
                       Integer remainingUses, Long expiresAt, long createdAt) {}
    public record Claim(String id, String childId, String itemId, String status, String note,
                        long createdAt, Long decidedAt) {}
    public record Action(String id, String childId, String itemId, String claimId, String kind,
                         int amount, String status, String note, Long dueAt, long createdAt,
                         Long appliedAt, Long reversedAt) {}
    public record LedgerEntry(String id, String actionId, int delta, String kind, long createdAt) {}
    public record Overview(long balance, List<Item> items, List<Claim> claims,
                           List<Action> actions, List<LedgerEntry> ledger) {}
}
