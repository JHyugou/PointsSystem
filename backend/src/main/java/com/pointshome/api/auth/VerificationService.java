package com.pointshome.api.auth;

import com.pointshome.api.common.ApiProblem;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class VerificationService {
    private static final long CODE_LIFETIME = 10 * 60_000L;
    private static final long SEND_COOLDOWN = 60_000L;
    private static final long IP_WINDOW = 60 * 60_000L;

    private final AccountStore accounts;
    private final CodeStore codes;
    private final Crypto crypto;
    private final VerificationMailSender sender;
    private final TransactionTemplate transactions;

    public VerificationService(AccountStore accounts, CodeStore codes, Crypto crypto,
                               VerificationMailSender sender, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.codes = codes;
        this.crypto = crypto;
        this.sender = sender;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public synchronized void requestRegistrationCode(String email, String remoteAddress) {
        // Keep the local lock until the database commit finishes, so simultaneous requests
        // cannot both create a code for the same address on this single-server deployment.
        transactions.executeWithoutResult(status -> requestWithinTransaction(
                email, remoteAddress, VerificationMailSender.Purpose.REGISTRATION));
    }

    public synchronized void requestPasswordResetCode(String email, String remoteAddress) {
        transactions.executeWithoutResult(status -> requestWithinTransaction(
                email, remoteAddress, VerificationMailSender.Purpose.PASSWORD_RESET));
    }

    private void requestWithinTransaction(String email, String remoteAddress,
                                          VerificationMailSender.Purpose purpose) {
        long now = System.currentTimeMillis();
        String ipHash = crypto.otpHash("ip", remoteAddress);
        Optional<CodeStore.IpRate> rate = codes.ipRateForUpdate(ipHash);
        if (rate.isPresent() && now - rate.get().windowStartedAt() < IP_WINDOW
                && rate.get().sentCount() >= 10) {
            throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求过于频繁，请稍后再试");
        }

        boolean registered = accounts.byEmail(email)
                .filter(account -> account.role() == Account.Role.GUARDIAN).isPresent();
        // Always accept the request without revealing whether the address has an account.
        if (registered != (purpose == VerificationMailSender.Purpose.PASSWORD_RESET)) return;

        Optional<CodeStore.EmailCode> previous = codes.byEmailForUpdate(email, purpose);
        if (previous.isPresent() && previous.get().nextSendAt() > now) {
            return;
        }

        String code = crypto.sixDigitCode();
        codes.saveCode(email, crypto.otpHash(email, code), now + CODE_LIFETIME,
                now + SEND_COOLDOWN, previous.isPresent(), purpose);
        int newCount = rate.isPresent() && now - rate.get().windowStartedAt() < IP_WINDOW
                ? rate.get().sentCount() + 1 : 1;
        long windowStart = newCount == 1 ? now : rate.get().windowStartedAt();
        codes.recordIp(ipHash, windowStart, newCount, rate.isPresent());

        try {
            sender.send(email, code, purpose);
        } catch (MailException ex) {
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_UNAVAILABLE", "邮件暂时无法发送，请稍后再试");
        }
    }

    void consumeRegistrationCode(String email, String suppliedCode) {
        consumeCode(email, suppliedCode, VerificationMailSender.Purpose.REGISTRATION);
    }

    void consumePasswordResetCode(String email, String suppliedCode) {
        consumeCode(email, suppliedCode, VerificationMailSender.Purpose.PASSWORD_RESET);
    }

    private void consumeCode(String email, String suppliedCode,
                             VerificationMailSender.Purpose purpose) {
        long now = System.currentTimeMillis();
        CodeStore.EmailCode stored = codes.byEmailForUpdate(email, purpose).orElseThrow(this::invalidCode);
        if (stored.expiresAt() <= now || stored.attempts() >= 5) throw invalidCode();
        if (!crypto.equalHashes(stored.hash(), crypto.otpHash(email, suppliedCode))) {
            codes.incrementAttempts(email, purpose);
            throw invalidCode();
        }
        codes.delete(email, purpose);
    }

    private ApiProblem invalidCode() {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_CODE", "验证码错误或已失效");
    }
}
