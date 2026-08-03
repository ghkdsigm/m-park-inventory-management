package com.mpark.wms.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 비싼 AI 엔드포인트(챗/사진검색/TTS/견적업로드)에 대한 사용자별 분당 호출 상한.
 * 인메모리 고정창(fixed window) — 폭주/오남용에 의한 비용 급증 방지. (10명 규모라 메모리·정확도 충분)
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int LIMIT = 40;        // 사용자별 분당 허용 호출 수(AI 엔드포인트 합산)
    private static final long WINDOW_MS = 60_000;

    private final CurrentUser currentUser;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitInterceptor(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String uid = currentUser.id();
        String key = uid != null ? uid : req.getRemoteAddr();
        long now = System.currentTimeMillis();

        Window w = windows.computeIfAbsent(key, k -> new Window(now));
        boolean blocked;
        synchronized (w) {
            if (now - w.start >= WINDOW_MS) { w.start = now; w.count = 0; }
            w.count++;
            blocked = w.count > LIMIT;
        }
        if (blocked) {
            res.setStatus(429); // Too Many Requests
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\"}");
            return false;
        }
        return true;
    }

    private static final class Window {
        long start;
        int count;
        Window(long start) { this.start = start; }
    }
}
