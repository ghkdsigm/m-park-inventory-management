package com.mpark.wms.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 멱등 처리 키 — 같은 request_id 로 온 재고 요청을 1회만 반영하기 위한 기록. */
@Getter
@Setter
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "before_qty")
    private Integer beforeQty;

    @Column(name = "after_qty")
    private Integer afterQty;

    private Integer delta;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void pre() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
