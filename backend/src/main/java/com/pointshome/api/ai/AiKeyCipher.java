package com.pointshome.api.ai;

import com.pointshome.api.common.ApiProblem;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AiKeyCipher {
    private final String configuredKey;
    private final SecureRandom random = new SecureRandom();

    public AiKeyCipher(@Value("${app.ai.master-key:}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    public String encrypt(String secret) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] combined = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (ApiProblem problem) {
            throw problem;
        } catch (Exception ignored) {
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "AI_KEY_ENCRYPTION_FAILED",
                    "无法保存 API 密钥，请检查服务端加密配置");
        }
    }

    public String decrypt(String value) {
        try {
            byte[] combined = Base64.getDecoder().decode(value);
            if (combined.length < 29) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, combined, 0, 12));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
        } catch (ApiProblem problem) {
            throw problem;
        } catch (Exception ignored) {
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "AI_KEY_DECRYPTION_FAILED",
                    "无法读取 API 密钥，请检查服务端主密钥");
        }
    }

    private SecretKeySpec key() {
        try {
            byte[] bytes = Base64.getDecoder().decode(configuredKey);
            if (bytes.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException ignored) {
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "AI_MASTER_KEY_MISSING",
                    "服务端尚未配置 AI 加密主密钥");
        }
    }
}
