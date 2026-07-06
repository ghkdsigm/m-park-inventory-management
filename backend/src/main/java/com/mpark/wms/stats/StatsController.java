package com.mpark.wms.stats;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** 일별 집계 조회 — db.js 의 getDailyStats / getTodayStats. */
@RestController
@RequestMapping("/api/daily-stats")
@RequiredArgsConstructor
public class StatsController {

    private final DailyStatsRepository repo;

    @GetMapping
    public List<DailyStats> recent(@RequestParam(defaultValue = "7") int days) {
        return repo.findAllByOrderByStatDateDesc(PageRequest.of(0, days));
    }

    @GetMapping("/today")
    public DailyStats today() {
        return repo.findById(LocalDate.now()).orElse(null);
    }

    /** 특정 기간(달력 월 등) 일별 집계 조회 */
    @GetMapping("/range")
    public List<DailyStats> range(@RequestParam String from, @RequestParam String to) {
        return repo.findByStatDateBetweenOrderByStatDateAsc(LocalDate.parse(from), LocalDate.parse(to));
    }
}
