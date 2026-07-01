package com.mpark.wms.audit;

import com.mpark.wms.audit.AuditDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 감사로그 조회 — db.js 의 listAuditLogs / auditTopUsers / topProductsBySku / topChangedSkus. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService service;

    @GetMapping("/audit-logs")
    public List<AuditLog> list(@RequestParam(required = false) String date,
                               @RequestParam(required = false) String module,
                               @RequestParam(required = false) String byUserId,
                               @RequestParam(defaultValue = "500") int max) {
        return service.list(date, module, byUserId, max);
    }

    @GetMapping("/audit/top-users")
    public List<TopUser> topUsers(@RequestParam(defaultValue = "10") int limit) { return service.topUsers(limit); }

    @GetMapping("/audit/top-products")
    public List<TopProduct> topProducts(@RequestParam(defaultValue = "10") int limit) { return service.topProductsBySku(limit); }

    @GetMapping("/audit/top-changed")
    public List<TopChanged> topChanged(@RequestParam(defaultValue = "10") int limit) { return service.topChangedSkus(limit); }
}
