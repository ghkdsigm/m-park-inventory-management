package com.mpark.wms.stock;

import com.mpark.wms.stock.StockDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 재고 작업 REST — 입고/출고/조정/이동/취소/연한/검증 (재고행 기준). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StockController {

    private final StockService service;

    @PostMapping("/stock/inbound")
    public StockResult inbound(@RequestBody InboundRequest r) { return service.inbound(r); }

    @PostMapping("/stock/outbound")
    public StockResult outbound(@RequestBody OutboundRequest r) { return service.outbound(r); }

    @PostMapping("/stock/adjust")
    public StockResult adjust(@RequestBody AdjustRequest r) { return service.adjust(r); }

    @PostMapping("/stock/transfer")
    public TransferResult transfer(@RequestBody TransferRequest r) { return service.transfer(r); }

    @PostMapping("/movements/{id}/void")
    public StockResult voidMovement(@PathVariable String id, @RequestBody(required = false) VoidRequest r) {
        return service.voidMovement(id, r == null ? null : r.reason());
    }

    @PostMapping("/stock/{stockId}/replace-lifecycle")
    public LifecycleResult replace(@PathVariable String stockId, @RequestBody(required = false) ReplaceRequest r) {
        return service.replaceLifecycle(stockId, r == null ? null : r.reason());
    }

    @PostMapping("/stock/{stockId}/verify")
    public void verify(@PathVariable String stockId, @RequestBody(required = false) VerifyRequest r) {
        service.verifyLocation(stockId, r == null ? null : r.name());
    }
}
