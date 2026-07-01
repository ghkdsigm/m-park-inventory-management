package com.mpark.wms.movement;

import com.mpark.wms.stock.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 원장/이력 조회 REST — db.js 의 listMovements/recentMovements/listLifecycleLogs/listLocationLogs. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MovementController {

    private final StockService stockService;

    /** skuId 있으면 해당 SKU, 없으면 전체 최근 */
    @GetMapping("/movements")
    public List<StockMovement> movements(@RequestParam(required = false) String skuId,
                                         @RequestParam(defaultValue = "100") int max) {
        return (skuId == null || skuId.isBlank())
                ? stockService.recentMovements(max)
                : stockService.listMovements(skuId, max);
    }

    @GetMapping("/lifecycle-logs")
    public List<LifecycleLog> lifecycleLogs(@RequestParam String skuId,
                                            @RequestParam(defaultValue = "50") int max) {
        return stockService.listLifecycleLogs(skuId, max);
    }

    @GetMapping("/location-logs")
    public List<LocationLog> locationLogs(@RequestParam String skuId,
                                          @RequestParam(defaultValue = "50") int max) {
        return stockService.listLocationLogs(skuId, max);
    }
}
