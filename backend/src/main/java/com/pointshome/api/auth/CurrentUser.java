package com.pointshome.api.auth;

public record CurrentUser(String id, Account.Role role) {}
