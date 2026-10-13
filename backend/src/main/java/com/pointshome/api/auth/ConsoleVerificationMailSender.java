package com.pointshome.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "console")
public class ConsoleVerificationMailSender implements VerificationMailSender {
    private static final Logger log = LoggerFactory.getLogger(ConsoleVerificationMailSender.class);

    @Override
    public void send(String email, String code, Purpose purpose) {
        log.warn("LOCAL DEVELOPMENT ONLY - {} verification code for {}: {}", purpose, email, code);
    }
}
