package com.mpark.wms.stock;

import java.time.LocalDateTime;

/** 재고 작업 요청/응답 DTO (새 모델: 재고행 기준). */
public final class StockDtos {
    private StockDtos() {}

    /** 입고 — SKU + 보관위치(필수) + 수량. 위치의 단지로 재고행을 find/create. */
    public record InboundRequest(String skuId, String storageLocationId, Integer qty, String memo, String reason) {}

    /** 출고 — 재고행 대상 */
    public record OutboundRequest(String stockId, Integer qty, String memo, String reason) {}

    /** 조정/실사 — 재고행 대상. type: adjust | audit */
    public record AdjustRequest(String stockId, String type, Integer value, String memo, String reason) {}

    public record StockResult(int before, int after, int delta) {}

    public record VoidRequest(String reason) {}
    public record ReplaceRequest(String reason) {}
    public record LifecycleResult(LocalDateTime replacedAt, LocalDateTime nextReplaceAt) {}
    public record VerifyRequest(String name) {}

    /** 재고이동 — 출발 재고행 → 도착 보관위치(필수). 같은 SKU의 도착 재고행에 합류. */
    public record TransferRequest(String stockId, String toStorageLocationId, Integer qty, String memo, String reason) {}
    public record TransferResult(String transferId, String fromStockId, String toStockId, int qty, boolean relocated) {}
}
