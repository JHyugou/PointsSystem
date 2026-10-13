package com.pointshome.api.auth;

import com.pointshome.api.common.ApiProblem;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LoginLimiter {
    private static final long WINDOW = 15 * 60_000L;
    private final Map<String, Window> windows = new HashMap<>();

    private record Window(long startedAt, int failures) {}

    public synchronized void check(String key) {
        long now = System.currentTimeMillis();
        Window window = windows.get(key);
        if (window != null && now - window.startedAt < WINDOW && window.failures >= 10) {
            throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_LIMITED", "登录尝试过多，请稍后再试");
        }
    }

    public synchronized void failed(String key) {
        long now = System.currentTimeMillis();
        Window window = windows.get(key);
        if (window == null || now - window.startedAt >= WINDOW) {
            if (windows.size() >= 10_000) {
                Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
                while (iterator.hasNext()) {
                    if (now - iterator.next().getValue().startedAt >= WINDOW) iterator.remove();
                }
            }
            if (windows.size() >= 10_000) {
                throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_LIMITED", "登录尝试过多，请稍后再试");
            }
            windows.put(key, new Window(now, 1));
        } else {
            windows.put(key, new Window(window.startedAt, window.failures + 1));
        }
    }

    public synchronized void succeeded(String key) {
        windows.remove(key);
    }
}
