package com.pointshome.api.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpVerificationMailSenderTest {
    @Test
    void resetEmailUsesASeparateSubjectAndTheConfiguredSender() {
        JavaMailSender mail = mock(JavaMailSender.class);
        SmtpVerificationMailSender sender = new SmtpVerificationMailSender(mail, "sender@qq.com");

        sender.send("recipient@163.com", "123456", VerificationMailSender.Purpose.PASSWORD_RESET);

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertEquals("sender@qq.com", message.getValue().getFrom());
        assertEquals("recipient@163.com", message.getValue().getTo()[0]);
        assertEquals("积分小屋找回密码验证码", message.getValue().getSubject());
        assertTrue(message.getValue().getText().contains("123456"));
    }
}
