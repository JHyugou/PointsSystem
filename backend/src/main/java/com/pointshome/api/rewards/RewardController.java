package com.pointshome.api.rewards;

import com.pointshome.api.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rewards")
public class RewardController {
    private final RewardService rewards;

    public RewardController(RewardService rewards) {
        this.rewards = rewards;
    }

    @GetMapping("/children/{childId}")
    public RewardService.Overview overview(@AuthenticationPrincipal CurrentUser user,
                                           @PathVariable String childId) {
        return rewards.overview(user, childId);
    }

    @GetMapping("/children/{childId}/redemptions")
    public RewardService.HistoryPage history(@AuthenticationPrincipal CurrentUser user,
                                              @PathVariable String childId,
                                              @RequestParam(defaultValue = "0") int offset) {
        return rewards.history(user, childId, offset);
    }

    @PostMapping("/children/{childId}/items")
    public ResponseEntity<RewardService.RewardItem> create(@AuthenticationPrincipal CurrentUser user,
                                                            @PathVariable String childId,
                                                            @Valid @RequestBody NewReward body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rewards.create(user, childId,
                body.name(), body.description(), body.cost(), body.rewardKind(), body.timeMinutes()));
    }

    @PostMapping("/items/{rewardId}/archive")
    public RewardService.RewardItem archive(@AuthenticationPrincipal CurrentUser user,
                                            @PathVariable String rewardId) {
        return rewards.archive(user, rewardId);
    }

    @PostMapping("/items/{rewardId}/restore")
    public RewardService.RewardItem restore(@AuthenticationPrincipal CurrentUser user,
                                            @PathVariable String rewardId) {
        return rewards.restore(user, rewardId);
    }

    @PostMapping("/items/{rewardId}/redeem")
    public ResponseEntity<RewardService.Redemption> redeem(@AuthenticationPrincipal CurrentUser user,
                                                            @PathVariable String rewardId,
                                                            @Valid @RequestBody Redeem body,
                                                            @RequestHeader(value = "X-Time-Device-Id", required = false) String deviceId,
                                                            @RequestHeader(value = "X-Time-Device-Key", required = false) String deviceKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rewards.redeem(user, rewardId,
                body.requestId(), deviceId, deviceKey));
    }

    @PostMapping("/redemptions/{redemptionId}/provide")
    public RewardService.Redemption provide(@AuthenticationPrincipal CurrentUser user,
                                            @PathVariable String redemptionId) {
        return rewards.markProvided(user, redemptionId);
    }

    @PostMapping("/redemptions/{redemptionId}/confirm")
    public RewardService.Redemption confirm(@AuthenticationPrincipal CurrentUser user,
                                            @PathVariable String redemptionId) {
        return rewards.confirmReceived(user, redemptionId);
    }

    public record NewReward(@NotBlank @Size(max = 80) String name,
                            @Size(max = 300) String description,
                            @Min(1) @Max(100000) int cost,
                            String rewardKind, @Min(1) @Max(1440) Integer timeMinutes) {}
    public record Redeem(@NotBlank String requestId) {}

    @PostMapping("/redemptions/{redemptionId}/reject")
    public RewardService.Redemption reject(@AuthenticationPrincipal CurrentUser user, @PathVariable String redemptionId) {
        return rewards.reject(user, redemptionId);
    }
    @PostMapping("/redemptions/{redemptionId}/cancel")
    public RewardService.Redemption cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable String redemptionId) {
        return rewards.cancelTimeRequest(user, redemptionId);
    }
}
