package com.mpark.wms.product;

import com.mpark.wms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 상품 — 코드 자동(P-000001). 대표/다중 이미지. */
@Getter
@Setter
@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false)
    private String name;

    private String maker = "";
    private String barcode = "";
    private String note = "";
    private String mainImageUrl = "";

    /** 다중 이미지 (jsonb → MySQL JSON). 배열 형태 그대로 직렬화 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private List<Object> images = new ArrayList<>();

    /** 표준 단가 — 품목(상품)의 단일 가격 출처. SKU 는 생성 시 이 값을 상속한다. */
    private BigDecimal price = BigDecimal.ZERO;

    // 위치 소속(단지 + 위치코드) — 같은 실물이라도 단지/위치가 다르면 별도 상품·코드.
    // SKU 는 생성 시 이 위치를 상속해 단일 재고행을 그 위치에 만든다.
    private String complexId;
    private String complexName = "";
    private String storageLocationId;
    private String storageLocationCode = "";
    private String zoneId;
    private String zoneName = "";
    private String subZoneId;
    private String subZoneName = "";
    private String locationLabel = "";

    private String categoryId;
    private String categoryName = "";
    private String productCodeId;
    private String productCodeName = "";
    private String productDetailId;
    private String productDetailName = "";
    private String pathLabel = "";

    /** SKU 순번 (상품별 SKU 코드 채번용) */
    private int skuSeq = 0;
}
