package com.mpark.wms.movement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 재고 원장(이동 이력). append-only — 수정/삭제하지 않고 취소(역분개) 전표를 추가한다.
 * created_at/updated_at 없이 'at' 만 사용하므로 BaseEntity 를 상속하지 않는다.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_movements")
public class StockMovement {

    @Id
    @Column(length = 36, updatable = false, nullable = false)
    private String id;

    private String skuId;
    private String stockId;
    private String skuCode;
    private String productName;

    /** in | out | adjust | audit | void */
    private String type;

    private int qty;
    private int delta;

    @Column(name = "before_qty")
    private int beforeQty;

    @Column(name = "after_qty")
    private int afterQty;

    private String complexId;
    private String complexName;
    private String pathLabel;
    private String memo = "";
    private String reason = "";
    private java.math.BigDecimal unitPrice; // 입고 실구매단가 (백오피스 입고에서만, null=미입력)
    // 출고 상세 (주로 out 에서 사용)
    private String usagePlace = "";   // 사용처
    private String requestDept = "";  // 요청부서
    private String requester = "";    // 요청자
    private String handler = "";      // 담당자
    private String byUserId;
    private String byName;

    private LocalDateTime at;

    // 취소(역분개)
    private boolean voided = false;
    private LocalDateTime voidedAt;
    private String voidedBy;
    private String voidReason = "";
    private String reversalOf;
    private String transferId;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (at == null) at = LocalDateTime.now();
    }
}
