package com.pointshome.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final VerificationService verification;
    private final AuthService auth;

    public AuthController(VerificationService verification, AuthService auth) {
        this.verification = verification;
        this.auth = auth;
    }

    @PostMapping("/verification-codes")
    public ResponseEntity<Void> requestCode(@Valid @RequestBody CodeRequest body,
                                            HttpServletRequest request) {
        verification.requestRegistrationCode(AuthService.normalizeEmail(body.email()),
                request.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset-codes")
    public ResponseEntity<Void> requestPasswordResetCode(@Valid @RequestBody CodeRequest body,
                                                         HttpServletRequest request) {
        verification.requestPasswordResetCode(AuthService.normalizeEmail(body.email()),
                request.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-resets")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordReset body) {
        auth.resetGuardianPassword(AuthService.normalizeEmail(body.email()),
                body.code(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/guardians")
    public ResponseEntity<AuthService.Session> register(@Valid @RequestBody RegisterGuardian body) {
        AuthService.Session session = auth.registerGuardian(AuthService.normalizeEmail(body.email()),
                body.code(), body.password(), body.displayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @PostMapping("/guardian-sessions")
    public AuthService.Session guardianLogin(@Valid @RequestBody GuardianLogin body,
                                             HttpServletRequest request) {
        return auth.guardianLogin(AuthService.normalizeEmail(body.email()),
                body.password(), request.getRemoteAddr());
    }

    @PostMapping("/child-sessions")
    public AuthService.Session childLogin(@Valid @RequestBody ChildLogin body,
                                          HttpServletRequest request) {
        return auth.childLogin(AuthService.normalizeUsername(body.username()),
                body.password(), request.getRemoteAddr());
    }

    @GetMapping("/me")
    public Account.View me(@AuthenticationPrincipal CurrentUser user) {
        return auth.me(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authorization) {
        auth.logout(authorization);
        return ResponseEntity.noContent().build();
    }

    public record CodeRequest(@NotBlank @Email @Size(max = 254) String email) {}
    public record RegisterGuardian(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
            @NotBlank @Size(min = 10, max = 72) String password,
            @NotBlank @Size(max = 80) String displayName) {}
    public record GuardianLogin(@NotBlank @Email String email, @NotBlank String password) {}
    public record ChildLogin(@NotBlank String username, @NotBlank String password) {}
    public record PasswordReset(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
            @NotBlank @Size(min = 10, max = 72) String newPassword) {}
}
