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

/** 보관위치 변경 이력 */
@Getter
@Setter
@Entity
@Table(name = "location_logs")
public class LocationLog {

    @Id
    @Column(length = 36, updatable = false, nullable = false)
    private String id;

    private String skuId;
    private String skuCode;
    private String productName;
    private String fromLabel;
    private String toLabel;
    private String storageLocationId;
    private String storageLocationCode;
    private String locationLabel;
    private String complexName;
    private String byUserId;
    private String byName;
    private LocalDateTime at;

    @PrePersist
    void pre() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (at == null) at = LocalDateTime.now();
    }
}
