package com.mpark.wms.stock;

import com.mpark.wms.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 재고행 — (SKU x 보관위치). 실제 수량/상태/누계/이동시각을 담는다.
 * 단지(complexId)는 보관위치에서 도출되지만, 조회/집계 편의를 위해 비정규화로 함께 보관한다.
 */
@Getter
@Setter
@Entity
@Table(name = "stock")
public class Stock extends BaseEntity {

    private String skuId;

    private String complexId;
    private String complexName = "";

    // 위치(필수) — 입고 시 반드시 지정
    private String storageLocationId;
    private String storageLocationCode = "";
    private String zoneId;
    private String zoneName = "";
    private String subZoneId;
    private String subZoneName = "";
    private String locationLabel = "";

    private int qty = 0;
    private int initialQty = 0;
    private int totalIn = 0;
    private int totalOut = 0;

    /** in_stock | low | out */
    private String status = "in_stock";

    private String lastMovedBy;
    private LocalDateTime lastMovedAt;
    private LocalDateTime locationVerifiedAt;
    private String locationVerifiedBy;

    // 연한관리 교체 이벤트(물리 재고 단위)
    private LocalDateTime lastReplacedAt;
    private LocalDateTime nextReplaceAt;
    private String lastReplacedBy;
}
