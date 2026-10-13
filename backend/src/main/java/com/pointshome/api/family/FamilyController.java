package com.pointshome.api.family;

import com.pointshome.api.auth.AuthService;
import com.pointshome.api.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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
@RequestMapping("/api/v1/family")
public class FamilyController {
    private final FamilyService family;

    public FamilyController(FamilyService family) {
        this.family = family;
    }

    @PostMapping("/children")
    public ResponseEntity<FamilyService.Member> createChild(@AuthenticationPrincipal CurrentUser user,
                                                              @Valid @RequestBody CreateChild body) {
        FamilyService.Member member = family.createChild(user,
                AuthService.normalizeUsername(body.username()), body.password(), body.displayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(member);
    }

    @GetMapping("/children")
    public List<FamilyService.Member> myChildren(@AuthenticationPrincipal CurrentUser user) {
        return family.myChildren(user);
    }

    @GetMapping("/guardians")
    public List<FamilyService.Member> myGuardians(@AuthenticationPrincipal CurrentUser user) {
        return family.myGuardians(user);
    }

    @PostMapping("/children/{childId}/password-reset")
    public ResponseEntity<Void> resetChildPassword(@AuthenticationPrincipal CurrentUser user,
                                                   @PathVariable String childId,
                                                   @Valid @RequestBody ResetChildPassword body) {
        family.resetChildPassword(user, childId, body.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/children/{childId}/invites")
    public FamilyService.InviteCode invite(@AuthenticationPrincipal CurrentUser user,
                                            @PathVariable String childId) {
        return family.inviteGuardian(user, childId);
    }

    @PostMapping("/invites/redeem")
    public FamilyService.Member redeem(@AuthenticationPrincipal CurrentUser user,
                                        @Valid @RequestBody RedeemInvite body) {
        return family.redeemInvite(user, body.code());
    }

    public record CreateChild(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{4,24}") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 80) String displayName) {}
    public record RedeemInvite(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String code) {}
    public record ResetChildPassword(@NotBlank @Size(min = 8, max = 72) String newPassword) {}
}
