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
        String categoryId,
        String categoryName,
        String productCodeId,
        String productCodeName,
        String productDetailId,
        String productDetailName,
        String pathLabel
) {}
