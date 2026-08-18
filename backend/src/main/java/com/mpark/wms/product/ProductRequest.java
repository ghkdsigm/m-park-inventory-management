package com.mpark.wms.product;

import java.math.BigDecimal;
import java.util.List;

/** 상품 생성/수정 요청. code/skuSeq 는 서버 관리 → 무시. */
record ProductRequest(
        String name,
        String maker,
        String barcode,
        String note,
        String mainImageUrl,
        List<Object> images,
        BigDecimal price,
        // 위치 소속(단지 + 위치코드)
        String complexId,
        String complexName,
        String storageLocationId,
        String storageLocationCode,
        String zoneId,
        String zoneName,
        String subZoneId,
        String subZoneName,
        String locationLabel,
        String categoryId,
        String categoryName,
        String productCodeId,
        String productCodeName,
        String productDetailId,
        String productDetailName,
        String pathLabel
) {}
