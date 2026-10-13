package com.pointshome.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Crypto {
    private final SecureRandom random = new SecureRandom();
    private final byte[] otpSecret;

    public Crypto(@Value("${app.security.otp-secret:}") String secret) {
        if (secret.length() < 32) {
            throw new IllegalStateException("APP_OTP_SECRET must contain at least 32 characters");
        }
        this.otpSecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sixDigitCode() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    public String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String otpHash(String email, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(otpSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((email + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC is unavailable", e);
        }
    }

    public String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public boolean equalHashes(String expectedHex, String actualHex) {
        return MessageDigest.isEqual(HexFormat.of().parseHex(expectedHex),
                HexFormat.of().parseHex(actualHex));
    }
}
