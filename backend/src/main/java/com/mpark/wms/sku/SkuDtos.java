package com.mpark.wms.sku;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SKU/재고 조회·집계 DTO. 새 모델: SKU=변형, 재고=stock(재고행). 목록 한 행은 (재고행 + SKU 변형)을
 * 합친 평면 DTO(StockRow)로 내려가 프론트 표시형과 호환된다.
 */
public final class SkuDtos {

    private SkuDtos() {}

    /** 검색/페이징 필터 (db.js skus.page 파라미터) */
    public record SkuFilter(
            String complexId, String categoryId, String productCodeId, String productDetailId, String productId, String skuId,
            String storageLocationId, String status, String search, String color, String releaseYear, String productionYear,
            BigDecimal priceMin, BigDecimal priceMax, Boolean lifecycleOnly,
            String sort, Integer page, Integer pageSize
    ) {}

    /** 재고 목록 한 행 = 재고행(stock) + SKU 변형 필드 */
    public record StockRow(
            String stockId, String skuId, String code, String productId, String productName,
            String spec, String color, String releaseYear, String productionYear, String purpose,
            String imageUrl, String productMainImageUrl, BigDecimal price, int safetyStock,
            String categoryId, String productCodeId, String productDetailId, String pathLabel,
            String complexId, String complexName,
            String storageLocationId, String storageLocationCode, String zoneId, String zoneName,
            String subZoneId, String subZoneName, String locationLabel,
            int qty, int totalIn, int totalOut, String status,
            LocalDateTime lastMovedAt, String lastMovedBy,
            boolean lifecycleEnabled, Integer cycleValue, String cycleUnit,
            LocalDateTime lastReplacedAt, LocalDateTime nextReplaceAt,
            BigDecimal dimW, BigDecimal dimL, BigDecimal dimH, BigDecimal dimD
    ) {}

    /** page() 반환: 재고행 + 집계 */
    public record SkuPageResult(List<StockRow> rows, long total, long totalQty, long lowCount, long outCount) {}

    /** 단지별 묶기 한 행 */
    public record ComplexGroupRow(String complexName, long skuCount, long totalQty, long lowCount, long outCount) {}

    /** 필터 옵션 */
    public record FilterOptions(List<String> colors, List<String> releaseYears, List<String> productionYears) {}

    /** 대시보드 요약 */
    public record DashboardSummary(
            long complexCount, long productCount, long skuCount,
            long totalQty, long lowCount, long outCount,
            List<StockRow> lowList, long lifeSoon, long lifeOver, List<StockRow> lifeList
    ) {}
}
