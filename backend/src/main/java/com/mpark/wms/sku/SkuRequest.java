package com.mpark.wms.sku;

import java.math.BigDecimal;

/**
 * SKU 생성/수정 요청. 서버 관리 필드(code, status, totalIn/Out, initialQty, qrGenerated,
 * lastMoved*, locationVerified*, lastReplaced*)는 받지 않거나 무시한다.
 * 위치는 set-location 으로 별도 변경하지만, 생성 시 초기 위치 지정도 허용한다.
 */
public record SkuRequest(
        String productId,
        String productName,
        String spec,
        String color,
        String releaseYear,
        String productionYear,
        String purpose,
        String imageUrl,
        String productMainImageUrl,
        BigDecimal price,
        Integer qty,
        Integer safetyStock,
        String complexId,
        String complexName,
        String categoryId,
        String productCodeId,
        String productDetailId,
        String pathLabel,
        // 위치(선택)
        String storageLocationId,
        String storageLocationCode,
        String zoneId,
        String zoneName,
        String subZoneId,
        String subZoneName,
        String locationLabel,
        // 연한
        Boolean lifecycleEnabled,
        Integer cycleValue,
        String cycleUnit,
        java.time.LocalDateTime nextReplaceAt,
        java.time.LocalDateTime lastReplacedAt,
        String replaceReason,
        String lifecycleNote,
        // 치수
        BigDecimal dimW,
        BigDecimal dimL,
        BigDecimal dimH,
        BigDecimal dimD
) {}
