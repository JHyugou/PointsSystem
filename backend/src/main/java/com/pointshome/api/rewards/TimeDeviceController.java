package com.pointshome.api.rewards;

import com.pointshome.api.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/app-time")
public class TimeDeviceController {
    private final TimeDeviceService devices;
    private final RewardService rewards;
    public TimeDeviceController(TimeDeviceService devices, RewardService rewards) {
        this.devices = devices; this.rewards = rewards;
    }
    @PostMapping("/children/{childId}/device")
    public TimeDeviceService.Registration register(@AuthenticationPrincipal CurrentUser user,
            @PathVariable String childId, @Valid @RequestBody NewDevice body) {
        return devices.register(user, childId, body.deviceId(), body.deviceName());
    }
    @GetMapping("/grants")
    public List<RewardService.TimeGrant> grants(@AuthenticationPrincipal CurrentUser user,
            @RequestHeader("X-Time-Device-Id") String deviceId,
            @RequestHeader("X-Time-Device-Key") String deviceKey) {
        return rewards.timeGrants(user, deviceId, deviceKey);
    }
    @PostMapping("/grants/{id}/received")
    public void received(@AuthenticationPrincipal CurrentUser user, @PathVariable String id,
            @RequestHeader("X-Time-Device-Id") String deviceId,
            @RequestHeader("X-Time-Device-Key") String deviceKey) {
        rewards.acknowledgeTime(user, id, deviceId, deviceKey);
    }
    public record NewDevice(@NotBlank @Size(min = 36, max = 36) String deviceId,
                            @NotBlank @Size(max = 100) String deviceName) {}
}
