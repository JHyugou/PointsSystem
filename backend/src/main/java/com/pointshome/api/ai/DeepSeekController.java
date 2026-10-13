package com.pointshome.api.ai;

import com.pointshome.api.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai/deepseek")
public class DeepSeekController {
    public record KeyDraft(@Size(max = 512) String apiKey) {}
    public record SaveSetting(@Size(max = 512) String apiKey,
                              @Size(max = 128) String modelId) {}

    private final DeepSeekService service;

    public DeepSeekController(DeepSeekService service) {
        this.service = service;
    }

    @GetMapping
    public DeepSeekService.SettingView setting(@AuthenticationPrincipal CurrentUser user) {
        return service.setting(user.id());
    }

    @PostMapping("/models/refresh")
    public DeepSeekService.ModelList refresh(@AuthenticationPrincipal CurrentUser user,
                                              @Valid @RequestBody KeyDraft body) {
        return service.refreshModels(user.id(), body.apiKey());
    }

    @PutMapping
    public DeepSeekService.SettingView save(@AuthenticationPrincipal CurrentUser user,
                                             @Valid @RequestBody SaveSetting body) {
        return service.save(user.id(), body.apiKey(), body.modelId());
    }

    @PostMapping("/test")
    public DeepSeekService.TestResult test(@AuthenticationPrincipal CurrentUser user) {
        return service.test(user.id());
    }

    @DeleteMapping
    public ResponseEntity<Void> remove(@AuthenticationPrincipal CurrentUser user) {
        service.remove(user.id());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
