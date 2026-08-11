package com.mpark.wms.sku;

import com.mpark.wms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * SKU = 품목 변형 정체성 (상품 + 규격/색상/치수 + 표준가 + 연한설정 + 분류).
 * 단지·위치·수량은 갖지 않는다 — 실제 재고는 stock(재고행) 테이블이 (SKU x 보관위치)로 관리.
 * 코드 자동(상품코드-001).
 */
@Getter
@Setter
@Entity
@Table(name = "skus")
public class Sku extends BaseEntity {

    @Column(nullable = false, unique = true, length = 60)
    private String code;

    private String productId;
    private String complexId;            // 단지 귀속 (같은 품목·규격도 단지별 별도 SKU)
    private String complexName = "";
    private String productName = "";
    private String spec = "";
    private String color = "";
    private String releaseYear = "";
    private String productionYear = "";
    private String purpose = "";
    private String imageUrl = "";
    private String productMainImageUrl = "";

    private BigDecimal price = BigDecimal.ZERO;
    private int safetyStock = 0;
    private boolean qrGenerated = true;

    private String categoryId;
    private String productCodeId;
    private String productDetailId;
    private String pathLabel = "";

    // 연한관리 설정(변형 단위) — 교체 이벤트/다음교체일은 재고행(stock)에 기록
    private boolean lifecycleEnabled = false;
    private Integer cycleValue = 0;
    private String cycleUnit = "month";
    private String replaceReason = "";
    private String lifecycleNote = "";

    // 치수(cm)
    @Column(name = "dim_w") private BigDecimal dimW;
    @Column(name = "dim_l") private BigDecimal dimL;
    @Column(name = "dim_h") private BigDecimal dimH;
    @Column(name = "dim_d") private BigDecimal dimD;
}
