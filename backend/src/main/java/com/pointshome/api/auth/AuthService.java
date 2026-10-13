package com.pointshome.api.auth;

import com.pointshome.api.common.ApiProblem;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final long SESSION_LIFETIME = 30L * 24 * 60 * 60_000;
    private final AccountStore accounts;
    private final SessionStore sessions;
    private final VerificationService verification;
    private final PasswordEncoder encoder;
    private final Crypto crypto;
    private final LoginLimiter limiter;

    public AuthService(AccountStore accounts, SessionStore sessions,
                       VerificationService verification, PasswordEncoder encoder,
                       Crypto crypto, LoginLimiter limiter) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.verification = verification;
        this.encoder = encoder;
        this.crypto = crypto;
        this.limiter = limiter;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional(noRollbackFor = ApiProblem.class)
    public Session registerGuardian(String email, String code, String password, String displayName) {
        if (accounts.byEmail(email).isPresent()) {
            throw new ApiProblem(HttpStatus.CONFLICT, "ACCOUNT_EXISTS", "这个邮箱已经注册");
        }
        verification.consumeRegistrationCode(email, code);
        Account guardian = new Account(UUID.randomUUID().toString(), Account.Role.GUARDIAN,
                email, null, displayName.trim(), encoder.encode(password));
        accounts.insert(guardian, System.currentTimeMillis());
        return newSession(guardian);
    }

    @Transactional
    public Session guardianLogin(String email, String password, String remoteAddress) {
        String key = crypto.sha256("guardian:" + email + ":" + remoteAddress);
        limiter.check(key);
        Account account = accounts.byEmail(email).orElse(null);
        if (account == null || account.role() != Account.Role.GUARDIAN
                || !encoder.matches(password, account.passwordHash())) {
            limiter.failed(key);
            throw invalidCredentials();
        }
        limiter.succeeded(key);
        return newSession(account);
    }

    @Transactional(noRollbackFor = ApiProblem.class)
    public void resetGuardianPassword(String email, String code, String newPassword) {
        verification.consumePasswordResetCode(email, code);
        Account guardian = accounts.byEmail(email)
                .filter(account -> account.role() == Account.Role.GUARDIAN)
                .orElseThrow(() -> new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_CODE",
                        "验证码错误或已失效"));
        accounts.updatePassword(guardian.id(), encoder.encode(newPassword));
        sessions.deleteForUser(guardian.id());
    }

    @Transactional
    public Session childLogin(String username, String password, String remoteAddress) {
        String key = crypto.sha256("child:" + username + ":" + remoteAddress);
        limiter.check(key);
        Account account = accounts.byUsername(username).orElse(null);
        if (account == null || account.role() != Account.Role.CHILD
                || !encoder.matches(password, account.passwordHash())) {
            limiter.failed(key);
            throw invalidCredentials();
        }
        limiter.succeeded(key);
        return newSession(account);
    }

    public Account.View me(CurrentUser user) {
        Account account = accounts.byId(user.id()).orElseThrow(() ->
                new ApiProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请重新登录"));
        return Account.View.of(account);
    }

    public void logout(String authorizationHeader) {
        String token = BearerAuthenticationFilter.tokenFromHeader(authorizationHeader);
        if (token != null) sessions.delete(crypto.sha256(token));
    }

    private Session newSession(Account account) {
        long now = System.currentTimeMillis();
        long expiresAt = now + SESSION_LIFETIME;
        String token = crypto.randomToken();
        sessions.insert(crypto.sha256(token), account.id(), now, expiresAt);
        return new Session(token, expiresAt, Account.View.of(account));
    }

    private ApiProblem invalidCredentials() {
        return new ApiProblem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
    }

    public record Session(String token, long expiresAt, Account.View account) {}
}
