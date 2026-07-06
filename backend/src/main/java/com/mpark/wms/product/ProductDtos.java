package com.mpark.wms.product;

import java.util.List;

/** 상품 조회/페이징 DTO (db.js products.managePage 파라미터). */
public final class ProductDtos {

    private ProductDtos() {}

    public record ProductFilter(
            String categoryId, String productCodeId, String productDetailId,
            String search, Integer page, Integer pageSize
    ) {}

    public record ProductPageResult(List<Product> rows, long total) {}
}
