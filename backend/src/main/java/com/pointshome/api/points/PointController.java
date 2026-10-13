package com.pointshome.api.points;

import com.pointshome.api.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/points")
public class PointController {
    private final PointService points;

    public PointController(PointService points) {
        this.points = points;
    }

    @GetMapping("/children/{childId}")
    public PointService.Overview overview(@AuthenticationPrincipal CurrentUser user,
                                           @PathVariable String childId) {
        return points.overview(user, childId);
    }

    @PostMapping("/children/{childId}/items")
    public ResponseEntity<PointService.Item> createItem(@AuthenticationPrincipal CurrentUser user,
                                                         @PathVariable String childId,
                                                         @Valid @RequestBody NewItem body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(points.createItem(user, childId,
                body.name(), body.points(), body.maxUses(), body.durationDays()));
    }

    @PostMapping("/items/{itemId}/grant")
    public PointService.Action grant(@AuthenticationPrincipal CurrentUser user,
                                     @PathVariable String itemId,
                                     @Valid @RequestBody Grant body) {
        return points.grant(user, itemId, body.note(), body.requestId());
    }

    @PostMapping("/children/{childId}/deductions")
    public PointService.Action deduct(@AuthenticationPrincipal CurrentUser user,
                                      @PathVariable String childId,
                                      @Valid @RequestBody Deduction body) {
        return points.deduct(user, childId, body.points(), body.reason(), body.requestId());
    }

    @PostMapping("/items/{itemId}/claims")
    public ResponseEntity<PointService.Claim> claim(@AuthenticationPrincipal CurrentUser user,
                                                     @PathVariable String itemId,
                                                     @Valid @RequestBody ClaimNote body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(points.claim(user, itemId, body.note()));
    }

    @PostMapping("/claims/{claimId}/approve")
    public PointService.Action approve(@AuthenticationPrincipal CurrentUser user,
                                       @PathVariable String claimId) {
        return points.approve(user, claimId);
    }

    @PostMapping("/claims/{claimId}/reject")
    public PointService.Claim reject(@AuthenticationPrincipal CurrentUser user,
                                     @PathVariable String claimId) {
        return points.reject(user, claimId);
    }

    @PostMapping("/actions/{actionId}/reverse")
    public PointService.Action reverse(@AuthenticationPrincipal CurrentUser user,
                                       @PathVariable String actionId) {
        return points.reverse(user, actionId);
    }

    public record NewItem(@NotBlank @Size(max = 80) String name,
                          @Min(-100000) @Max(100000) int points,
                          @Min(1) @Max(10000) Integer maxUses,
                          @Min(1) @Max(3650) Integer durationDays) {}
    public record Grant(@Size(max = 300) String note, @NotBlank String requestId) {}
    public record Deduction(@Min(1) @Max(100000) int points,
                            @NotBlank @Size(max = 300) String reason,
                            @NotBlank String requestId) {}
    public record ClaimNote(@Size(max = 300) String note) {}
}
