package com.mpark.wms.location;

import com.mpark.wms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 상세구역 */
@Getter
@Setter
@Entity
@Table(name = "sub_zones")
public class SubZone extends BaseEntity {

    @Column(nullable = false)
    private String name;

    /** warehouse(재고창고) | usage(사용처) | common(공용) */
    private String type = "warehouse";

    private String zoneId;
    private String zoneName = "";
    private String complexId;
    private String complexName = "";
}
