package com.mpark.wms.location;

import com.mpark.wms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 보관위치 — 코드 자동(LOC-000001) */
@Getter
@Setter
@Entity
@Table(name = "storage_locations")
public class StorageLocation extends BaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    private String name = "";
    private String complexId;
    private String complexName = "";
    private String zoneId;
    private String zoneName = "";
    private String subZoneId;
    private String subZoneName = "";
    private String locationLabel = "";
}
