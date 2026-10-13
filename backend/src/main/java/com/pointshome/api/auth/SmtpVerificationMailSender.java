package com.pointshome.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "smtp", matchIfMissing = true)
public class SmtpVerificationMailSender implements VerificationMailSender {
    private final JavaMailSender sender;
    private final String from;

    public SmtpVerificationMailSender(JavaMailSender sender, @Value("${app.mail.from:}") String from) {
        if (from.isBlank()) {
            throw new IllegalStateException("MAIL_FROM is required in SMTP mode");
        }
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(String email, String code, Purpose purpose) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(purpose == Purpose.REGISTRATION
                ? "积分小屋注册验证码" : "积分小屋找回密码验证码");
        message.setText("你的" + (purpose == Purpose.REGISTRATION ? "注册" : "找回密码")
                + "验证码是 " + code + "，10 分钟内有效。请勿告诉他人。");
        sender.send(message);
    }
}
