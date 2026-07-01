package com.mpark.wms.master;

import com.mpark.wms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 제품상세코드 — 코드 자동(PCD-000001) */
@Getter
@Setter
@Entity
@Table(name = "product_details")
public class ProductDetail extends BaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description = "";

    private String productCodeId;
    private String productCodeName = "";
    private String categoryId;
    private String categoryName = "";
    private String pathLabel = "";
}
