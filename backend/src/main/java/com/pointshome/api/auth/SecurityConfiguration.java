package com.pointshome.api.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, BearerAuthenticationFilter bearer) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/verification-codes",
                                "/api/v1/auth/password-reset-codes",
                                "/api/v1/auth/password-resets",
                                "/api/v1/auth/guardians",
                                "/api/v1/auth/guardian-sessions",
                                "/api/v1/auth/child-sessions").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/family/children",
                                "/api/v1/family/children/*/invites",
                                "/api/v1/family/children/*/password-reset",
                                "/api/v1/family/invites/redeem").hasAuthority("GUARDIAN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/family/children").hasAuthority("GUARDIAN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/family/guardians").hasAuthority("CHILD")
                        .requestMatchers("/api/v1/ai/**").hasAuthority("GUARDIAN")
                        .requestMatchers("/api/v1/homework/**").hasAuthority("GUARDIAN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, error) ->
                                writeError(response, 401, "UNAUTHORIZED", "请先登录"))
                        .accessDeniedHandler((request, response, error) ->
                                writeError(response, 403, "FORBIDDEN", "没有操作权限")))
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeError(HttpServletResponse response, int status,
                                   String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
