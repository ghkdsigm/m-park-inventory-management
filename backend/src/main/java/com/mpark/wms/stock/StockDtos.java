package com.mpark.wms.stock;

import java.time.LocalDateTime;

/** 재고 작업 요청/응답 DTO (새 모델: 재고행 기준). */
public final class StockDtos {
    private StockDtos() {}

    /** 입고 — SKU + 보관위치(필수) + 수량 (+ 실구매단가 unitPrice, 백오피스 입고에서만). 위치의 단지로 재고행을 find/create. */
    public record InboundRequest(String skuId, String storageLocationId, Integer qty, String memo, String reason, String requestId,
                                 java.math.BigDecimal unitPrice) {}

    /** 출고 — 재고행 대상 (+ 출고 상세: 사용처/요청부서/요청자/담당자) */
    public record OutboundRequest(String stockId, Integer qty, String memo, String reason,
                                  String usagePlace, String requestDept, String requester, String handler, String requestId) {}

    /** 조정/실사 — 재고행 대상. type: adjust | audit */
    public record AdjustRequest(String stockId, String type, Integer value, String memo, String reason, String requestId) {}

    public record StockResult(int before, int after, int delta) {}

    public record VoidRequest(String reason) {}
    public record ReplaceRequest(String reason) {}
    public record LifecycleResult(LocalDateTime replacedAt, LocalDateTime nextReplaceAt) {}
    public record VerifyRequest(String name) {}

    /** 실사 오차 정상처리 — 재고행 대상 + 사유 */
    public record AuditResolveRequest(String reason) {}

    /** 재고이동 — SKU=단일 위치. 그 SKU의 재고행을 도착 위치코드로 relocate(수량 그대로, 코드 불변).
     *  stockId 또는 skuId 중 하나로 대상 지정. qty 는 사용하지 않음(전량 relocate). */
    public record TransferRequest(String stockId, String skuId, String toStorageLocationId, Integer qty, String memo, String reason, String requestId) {}
    public record TransferResult(String transferId, String fromStockId, String toStockId, int qty, boolean relocated) {}
}
