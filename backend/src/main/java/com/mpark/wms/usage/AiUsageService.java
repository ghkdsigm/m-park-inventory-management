package com.mpark.wms.usage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * AI 사용량 기록/집계. record()는 독립 트랜잭션(REQUIRES_NEW)이라 호출자 컨텍스트(스트리밍 스레드·readOnly 등)와
 * 무관하게 안전하게 쓰고, 실패해도 본 기능을 막지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private final AiUsageRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String userId, String userName, String feature, String model,
                       int prompt, int completion, int total, int chars) {
        try {
            int t = total > 0 ? total : (prompt + completion);
            if (t == 0 && chars == 0) return; // 기록할 사용량 없음
            AiUsage u = new AiUsage();
            u.setUserId(userId);
            u.setUserName(userName == null ? "" : userName);
            u.setFeature(feature);
            u.setModel(model == null ? "" : model);
            u.setPromptTokens(prompt);
            u.setCompletionTokens(completion);
            u.setTotalTokens(t);
            u.setCharCount(chars);
            repo.save(u);
        } catch (Exception ignored) {
            // 사용량 기록 실패가 챗봇/견적 처리를 막지 않도록 무시
        }
    }

    /** 최근 days 일 사용량 요약 — 계정별 + 기능별 + 총계. days=0 이면 '오늘'(자정부터). */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(int days) {
        int d = Math.max(0, Math.min(days, 365));
        LocalDateTime from = (d == 0)
                ? java.time.LocalDate.now().atStartOfDay()      // 오늘 자정부터
                : LocalDateTime.now().minusDays(d);

        List<Map<String, Object>> byUser = new ArrayList<>();
        long grandTokens = 0, grandChars = 0, grandCalls = 0;
        for (Object[] r : repo.summaryByUser(from)) {
            long total = num(r[4]);
            grandTokens += total;
            grandChars += num(r[5]);
            grandCalls += num(r[6]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", r[0]);
            m.put("userName", r[1]);
            m.put("promptTokens", num(r[2]));
            m.put("completionTokens", num(r[3]));
            m.put("totalTokens", total);
            m.put("charCount", num(r[5]));
            m.put("calls", num(r[6]));
            byUser.add(m);
        }

        List<Map<String, Object>> byFeature = new ArrayList<>();
        for (Object[] r : repo.summaryByFeature(from)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feature", r[0]);
            m.put("totalTokens", num(r[1]));
            m.put("charCount", num(r[2]));
            m.put("calls", num(r[3]));
            m.put("promptTokens", num(r[4]));
            m.put("completionTokens", num(r[5]));
            byFeature.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("totalTokens", grandTokens);
        out.put("totalChars", grandChars);
        out.put("totalCalls", grandCalls);
        out.put("byUser", byUser);
        out.put("byFeature", byFeature);
        return out;
    }

    private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
}
