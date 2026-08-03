package com.mpark.wms.usage;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** AI 사용량 모니터링 (관리자 전용 — SecurityConfig 에서 /api/ai-usage/** 을 ADMIN 으로 제한). */
@RestController
@RequestMapping("/api/ai-usage")
@RequiredArgsConstructor
public class AiUsageController {

    private final AiUsageService service;

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(value = "days", defaultValue = "30") int days) {
        return service.summary(days);
    }
}
