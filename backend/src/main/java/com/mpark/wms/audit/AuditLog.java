package com.mpark.wms.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** 감사로그 — 누가 무엇을 바꿨나. (Postgres audit_trigger/log_audit 대체) */
@Getter
@Setter
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @Column(length = 36, updatable = false, nullable = false)
    private String id;

    private String module;     // 기준정보 | 상품관리 | 위치관리 | 입/출고관리 | 재고관리
    private String tableName;
    private String action;     // 생성 | 수정 | 삭제 | 입고 | 출고 | 재고조정 | 재고실사 | 위치변경 | 교체
    private String rowId;
    private String label;      // 코드 또는 이름
    private String name = "";   // 상품명/이름
    private String byUserId;
    private String byName;
    private LocalDateTime at;

    @PrePersist
    void pre() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (at == null) at = LocalDateTime.now();
    }
}
