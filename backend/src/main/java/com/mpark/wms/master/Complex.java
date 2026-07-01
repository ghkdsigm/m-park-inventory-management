package com.mpark.wms.master;

import com.mpark.wms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 단지 — 코드 직접 입력 */
@Getter
@Setter
@Entity
@Table(name = "complexes")
public class Complex extends BaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description = "";
}
