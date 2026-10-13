package com.pointshome.api.family;

import com.pointshome.api.auth.Account;
import com.pointshome.api.auth.AccountStore;
import com.pointshome.api.auth.Crypto;
import com.pointshome.api.auth.CurrentUser;
import com.pointshome.api.auth.SessionStore;
import com.pointshome.api.common.ApiProblem;
import com.pointshome.api.points.PointService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FamilyService {
    private static final long INVITE_LIFETIME = 10 * 60_000L;
    private final AccountStore accounts;
    private final InviteStore invites;
    private final PasswordEncoder encoder;
    private final Crypto crypto;
    private final PointService points;
    private final SessionStore sessions;

    public FamilyService(AccountStore accounts, InviteStore invites, PasswordEncoder encoder, Crypto crypto,
                         PointService points, SessionStore sessions) {
        this.accounts = accounts;
        this.invites = invites;
        this.encoder = encoder;
        this.crypto = crypto;
        this.points = points;
        this.sessions = sessions;
    }

    @Transactional
    public Member createChild(CurrentUser user, String username, String password, String displayName) {
        requireGuardian(user);
        if (accounts.byUsername(username).isPresent()) {
            throw new ApiProblem(HttpStatus.CONFLICT, "USERNAME_EXISTS", "这个孩子账号名已被使用");
        }
        long now = System.currentTimeMillis();
        Account child = new Account(UUID.randomUUID().toString(), Account.Role.CHILD,
                null, username, displayName.trim(), encoder.encode(password));
        accounts.insert(child, now);
        points.createAccount(child.id());
        accounts.link(user.id(), child.id(), now);
        return Member.of(child);
    }

    public List<Member> myChildren(CurrentUser user) {
        requireGuardian(user);
        return accounts.childrenOf(user.id()).stream().map(Member::of).toList();
    }

    public List<Member> myGuardians(CurrentUser user) {
        if (user.role() != Account.Role.CHILD) {
            throw new ApiProblem(HttpStatus.FORBIDDEN, "FORBIDDEN", "只有孩子可以查看自己的家长");
        }
        return accounts.guardiansOf(user.id()).stream().map(Member::of).toList();
    }

    @Transactional
    public void resetChildPassword(CurrentUser user, String childId, String newPassword) {
        requireGuardian(user);
        if (!accounts.isLinked(user.id(), childId)) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "CHILD_NOT_FOUND", "找不到这个孩子");
        }
        accounts.updatePassword(childId, encoder.encode(newPassword));
        sessions.deleteForUser(childId);
    }

    @Transactional
    public InviteCode inviteGuardian(CurrentUser user, String childId) {
        requireGuardian(user);
        if (!accounts.isLinked(user.id(), childId)) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "CHILD_NOT_FOUND", "找不到这个孩子");
        }
        String code = crypto.randomToken();
        long expiresAt = System.currentTimeMillis() + INVITE_LIFETIME;
        invites.insert(crypto.sha256(code), childId, user.id(), expiresAt);
        return new InviteCode(code, expiresAt);
    }

    @Transactional
    public Member redeemInvite(CurrentUser user, String code) {
        requireGuardian(user);
        long now = System.currentTimeMillis();
        InviteStore.Invite invite = invites.byHashForUpdate(crypto.sha256(code))
                .orElseThrow(this::invalidInvite);
        if (invite.usedAt() != null || invite.expiresAt() <= now) throw invalidInvite();
        if (accounts.isLinked(user.id(), invite.childId())) {
            throw new ApiProblem(HttpStatus.CONFLICT, "ALREADY_LINKED", "你已经关联了这个孩子");
        }
        accounts.link(user.id(), invite.childId(), now);
        invites.markUsed(invite.hash(), now);
        return accounts.byId(invite.childId()).map(Member::of).orElseThrow(this::invalidInvite);
    }

    private void requireGuardian(CurrentUser user) {
        if (user == null || user.role() != Account.Role.GUARDIAN) {
            throw new ApiProblem(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅家长可以执行此操作");
        }
    }

    private ApiProblem invalidInvite() {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INVITE", "邀请码无效或已过期");
    }

    public record Member(String id, String displayName, String username) {
        static Member of(Account account) {
            return new Member(account.id(), account.displayName(), account.username());
        }
    }

    public record InviteCode(String code, long expiresAt) {}
}
