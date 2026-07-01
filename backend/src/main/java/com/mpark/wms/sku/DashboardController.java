package com.mpark.wms.sku;

import com.mpark.wms.sku.SkuDtos.DashboardSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 대시보드 요약 — 프론트 db.js 의 skus.dashboardSummary() 대응. */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final SkuService service;

    @GetMapping("/summary")
    public DashboardSummary summary() { return service.dashboardSummary(); }
}
