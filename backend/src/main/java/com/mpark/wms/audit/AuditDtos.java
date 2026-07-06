package com.mpark.wms.audit;

/** 감사 대시보드 집계 DTO (db.js auditTopUsers/topProductsBySku/topChangedSkus 반환형과 일치) */
public final class AuditDtos {
    private AuditDtos() {}

    public record TopUser(String name, long cnt) {}

    public record TopProduct(String productId, String productName, long skuCount) {}

    public record TopChanged(String rowId, String label, String productName, long cnt) {}
}
