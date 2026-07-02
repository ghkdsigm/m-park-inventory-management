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

    // 재고실사 — 마지막 실사 결과 스냅샷 + 오차 정상처리 정보
    private LocalDateTime lastAuditedAt;
    private String lastAuditedBy;
    private Integer lastAuditDiff;      // 실사 당시 오차(실사수량 - 시스템수량)
    private Integer lastAuditCounted;   // 실사 당시 확정 수량
    /** null=미확정 | mismatch=오차 미처리(비정상) | ok=정상(오차 0 또는 사유 정상처리) */
    private String auditStatus;
    private LocalDateTime auditResolvedAt;
    private String auditResolvedBy;
    private String auditResolveReason = "";

    // 연한관리 교체 이벤트(물리 재고 단위)
    private LocalDateTime lastReplacedAt;
    private LocalDateTime nextReplaceAt;
    private String lastReplacedBy;
}
