package com.pointshome.api.ai;

import com.pointshome.api.common.ApiProblem;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeepSeekService {
    public record SettingView(boolean configured, String modelId, Long updatedAt) {}
    public record ModelList(List<DeepSeekGateway.Model> models, long fetchedAt) {}
    public record TestResult(boolean ok, String modelId) {}

    private final AiSettingStore store;
    private final AiKeyCipher cipher;
    private final DeepSeekGateway gateway;

    public DeepSeekService(AiSettingStore store, AiKeyCipher cipher, DeepSeekGateway gateway) {
        this.store = store;
        this.cipher = cipher;
        this.gateway = gateway;
    }

    public SettingView setting(String guardianId) {
        return store.find(guardianId)
                .map(saved -> new SettingView(true, saved.modelId(), saved.updatedAt()))
                .orElseGet(() -> new SettingView(false, null, null));
    }

    public ModelList refreshModels(String guardianId, String draftKey) {
        String key = usableKey(guardianId, draftKey);
        return new ModelList(gateway.models(key), System.currentTimeMillis());
    }

    @Transactional
    public SettingView save(String guardianId, String draftKey, String modelId) {
        if (modelId == null || modelId.isBlank() || modelId.length() > 128) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "AI_MODEL_REQUIRED", "请选择模型");
        }
        String key = usableKey(guardianId, draftKey);
        DeepSeekGateway.Model chosen = gateway.models(key).stream()
                .filter(model -> model.id().equals(modelId))
                .findFirst()
                .orElseThrow(() -> new ApiProblem(HttpStatus.BAD_REQUEST, "AI_MODEL_UNAVAILABLE",
                        "该模型不在 DeepSeek 当前的在线列表中，请刷新后重选"));
        if (!chosen.acceptsImages()) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "AI_MODEL_NO_IMAGES",
                    "作业图片需要支持图片输入的模型，请选择其他模型");
        }
        long now = System.currentTimeMillis();
        store.save(guardianId, cipher.encrypt(key), modelId, now);
        return new SettingView(true, modelId, now);
    }

    public TestResult test(String guardianId) {
        AiSettingStore.Setting saved = store.find(guardianId)
                .orElseThrow(() -> new ApiProblem(HttpStatus.BAD_REQUEST, "AI_NOT_CONFIGURED",
                        "请先保存 API 密钥和模型"));
        gateway.probe(cipher.decrypt(saved.encryptedApiKey()), saved.modelId());
        return new TestResult(true, saved.modelId());
    }

    public void remove(String guardianId) {
        store.delete(guardianId);
    }

    private String usableKey(String guardianId, String draftKey) {
        if (draftKey != null && !draftKey.isBlank()) {
            String key = draftKey.strip();
            if (!key.matches("[\\x21-\\x7E]{10,512}")) {
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "AI_INVALID_KEY_FORMAT",
                        "API 密钥格式有误，请重新填写");
            }
            return key;
        }
        return store.find(guardianId)
                .map(saved -> cipher.decrypt(saved.encryptedApiKey()))
                .orElseThrow(() -> new ApiProblem(HttpStatus.BAD_REQUEST, "AI_API_KEY_REQUIRED",
                        "请先填写 DeepSeek API 密钥"));
    }
}
