package com.mpark.wms.movement;

import com.mpark.wms.stock.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** 원장/이력 조회 REST — db.js 의 listMovements/recentMovements/listLifecycleLogs/listLocationLogs. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MovementController {

    private final StockService stockService;

    /** date 있으면 그 날짜, skuId 있으면 해당 SKU, 둘 다 없으면 전체 최근 */
    @GetMapping("/movements")
    public List<StockMovement> movements(@RequestParam(required = false) String skuId,
                                         @RequestParam(required = false) String date,
                                         @RequestParam(defaultValue = "100") int max) {
        if (date != null && !date.isBlank()) return stockService.movementsByDate(LocalDate.parse(date), max);
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
