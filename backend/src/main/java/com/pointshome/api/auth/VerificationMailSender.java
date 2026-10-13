package com.pointshome.api.auth;

public interface VerificationMailSender {
    enum Purpose { REGISTRATION, PASSWORD_RESET }

    void send(String email, String code, Purpose purpose);
}
