package com.pointshome.api.auth;

public record Account(
        String id,
        Role role,
        String email,
        String username,
        String displayName,
        String passwordHash
) {
    public enum Role { GUARDIAN, CHILD }

    public record View(String id, Role role, String email, String username, String displayName) {
        public static View of(Account account) {
            return new View(account.id(), account.role(), account.email(),
                    account.username(), account.displayName());
        }
    }
}
