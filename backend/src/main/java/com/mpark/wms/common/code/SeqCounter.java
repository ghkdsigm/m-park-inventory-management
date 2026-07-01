package com.mpark.wms.common.code;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 자동코드 시퀀스 카운터. (Postgres 시퀀스 대체 — MySQL 은 시퀀스가 없음)
 * 행 단위 비관적 락으로 동시성 보장.
 */
@Getter
@Setter
@Entity
@Table(name = "seq_counters")
public class SeqCounter {

    @Id
    @Column(name = "name", length = 50)
    private String name;

    @Column(name = "val", nullable = false)
    private long val;
}
